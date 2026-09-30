package com.bdreview.platform.search;

import com.bdreview.platform.business.BusinessResponse;
import com.bdreview.platform.business.BusinessService;
import com.bdreview.platform.common.PageRequestDefaults;
import com.bdreview.platform.common.PageResponse;
import com.bdreview.platform.search.SearchIntent.PricePreference;
import com.bdreview.platform.search.SearchLexicon.Concept;
import com.bdreview.platform.search.SearchLexicon.ConceptType;
import com.bdreview.platform.search.SearchReferenceData.AreaRef;
import com.bdreview.platform.search.SmartSearchRepository.Criteria;
import com.bdreview.platform.search.SmartSearchRepository.Hit;
import com.bdreview.platform.search.SmartSearchRepository.HitPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Smart search: parse intent → run the ranked query → if nothing matches, relax one constraint at a
 * time (always saying which) → attach short match reasons. Explicit filters the user picked in the
 * UI (category, area, price, rating) are hard constraints and are never relaxed; only constraints
 * that were inferred from the typed text are.
 */
@Service
public class SmartSearchService {

    private static final Logger log = LoggerFactory.getLogger(SmartSearchService.class);

    static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    static final double NEAR_ME_RADIUS_M = 5_000;
    static final double NEAR_ME_WIDE_RADIUS_M = 15_000;
    static final double NEAR_AREA_RADIUS_M = 10_000;
    static final double FUZZY_NAME_THRESHOLD = 0.35;
    static final double RATING_WEIGHT = 0.6;
    static final double RATING_WEIGHT_BEST = 1.5;

    /** Inferred constraints that may be dropped, in the order they're given up. */
    enum Relax { OPEN_NOW, PRICE, NEARBY, BROADER, ANYWHERE, FUZZY }

    /** Tried top to bottom; each is intersected with what actually applies to the query. */
    private static final List<EnumSet<Relax>> LADDER = List.of(
            EnumSet.noneOf(Relax.class),
            EnumSet.of(Relax.OPEN_NOW),
            EnumSet.of(Relax.OPEN_NOW, Relax.PRICE),
            EnumSet.of(Relax.OPEN_NOW, Relax.PRICE, Relax.NEARBY),
            EnumSet.of(Relax.OPEN_NOW, Relax.PRICE, Relax.BROADER),
            EnumSet.of(Relax.OPEN_NOW, Relax.PRICE, Relax.BROADER, Relax.NEARBY),
            EnumSet.of(Relax.OPEN_NOW, Relax.PRICE, Relax.ANYWHERE),
            EnumSet.of(Relax.OPEN_NOW, Relax.PRICE, Relax.BROADER, Relax.ANYWHERE),
            EnumSet.of(Relax.OPEN_NOW, Relax.PRICE, Relax.BROADER, Relax.ANYWHERE, Relax.FUZZY));

    public record Request(String q, UUID categoryId, UUID areaId, String priceTier, Double minRating,
                          Double lat, Double lng, String sort, int page, int size, String lang, String sessionId) {
        public Request(String q, UUID categoryId, UUID areaId, String priceTier, Double minRating,
                       Double lat, Double lng, String sort, int page, int size, String lang) {
            this(q, categoryId, areaId, priceTier, minRating, lat, lng, sort, page, size, lang, null);
        }
    }

    private final SearchIntentParser parser;
    private final SearchLexicon lexicon;
    private final SearchReferenceData referenceData;
    private final SmartSearchRepository repository;
    private final BusinessService businessService;

    public SmartSearchService(SearchIntentParser parser, SearchLexicon lexicon, SearchReferenceData referenceData,
                              SmartSearchRepository repository, BusinessService businessService) {
        this.parser = parser;
        this.lexicon = lexicon;
        this.referenceData = referenceData;
        this.repository = repository;
        this.businessService = businessService;
    }

    // ------------------------------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------------------------------

    public SmartSearchResponse search(Request r) {
        long started = System.nanoTime();
        SearchIntent intent = parser.parse(r.q());
        String lang = "bn".equals(r.lang()) ? "bn" : "en";
        AreaRef area = intent.areaId() == null ? null
                : referenceData.get().areasByLowerName().get(intent.areaName().toLowerCase(Locale.ROOT));
        boolean userCoords = r.lat() != null && r.lng() != null;
        Context ctx = new Context(intent, r, area, userCoords);

        int size = PageRequestDefaults.clamp(r.size());
        int page = Math.max(0, r.page());
        int offset = page * size;
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        String dow = now.getDayOfWeek().name();
        String prevDow = now.getDayOfWeek().minus(1).name();

        HitPage result = new HitPage(List.of(), 0);
        EnumSet<Relax> used = EnumSet.noneOf(Relax.class);
        List<EnumSet<Relax>> steps = applicableSteps(ctx);
        for (EnumSet<Relax> step : steps) {
            Criteria c = criteria(ctx, step);
            HitPage hp = repository.search(c, now.toLocalDate(), now.toLocalTime(), dow, prevDow, size, offset);
            if (hp.hits().isEmpty() && offset > 0) {
                // Past the last page of this step — probe whether the step itself has results.
                HitPage probe = repository.search(c, now.toLocalDate(), now.toLocalTime(), dow, prevDow, 1, 0);
                hp = new HitPage(List.of(), probe.total());
            }
            if (hp.total() > 0) {
                result = hp;
                used = step;
                break;
            }
        }

        List<UUID> ids = result.hits().stream().map(Hit::id).toList();
        List<BusinessResponse> cards = businessService.listingResponsesByIds(ids);
        Map<UUID, List<String>> reasons = new LinkedHashMap<>();
        Set<UUID> shown = new java.util.HashSet<>();
        cards.forEach(b -> shown.add(b.id()));
        for (Hit h : result.hits()) {
            if (shown.contains(h.id())) {
                List<String> rs = matchReasons(ctx, used, h, lang);
                if (!rs.isEmpty()) {
                    reasons.put(h.id(), rs);
                }
            }
        }

        int totalPages = (int) Math.ceil(result.total() / (double) size);
        PageResponse<BusinessResponse> pageResponse = new PageResponse<>(cards, page, size, result.total(), totalPages);
        String notice = result.total() > 0 ? notice(ctx, used, lang) : unknownAreaNotice(ctx, lang);
        if (log.isDebugEnabled()) {
            log.debug("smart-search q='{}' intent={} relaxed={} total={} in {}ms", intent.normalizedQuery(), intent,
                    used, result.total(), (System.nanoTime() - started) / 1_000_000);
        }
        return new SmartSearchResponse(pageResponse, summary(intent, area, lang), notice,
                intent.nearMe() && !userCoords, reasons, page == 0 ? sponsoredFor(intent, r, cards) : null);
    }

    /**
     * V58: one sponsored result matching the query's category kinds / area — computed AFTER and
     * independently of the organic ranking above, which it never touches.
     */
    private com.bdreview.platform.promo.SponsoredService.SponsoredBusiness sponsoredFor(SearchIntent intent, Request r,
                                                                                        List<BusinessResponse> organic) {
        if (sponsoredService == null) {
            return null;
        }
        Set<String> kinds = new LinkedHashSet<>();
        intent.concepts().forEach(c -> kinds.add(c.kind()));
        Set<UUID> onPage = organic.stream().map(BusinessResponse::id).collect(java.util.stream.Collectors.toSet());
        try {
            return sponsoredService.forSearch(
                    new com.bdreview.platform.promo.SponsoredService.Viewer(null, r.lat(), r.lng(), r.sessionId()),
                    kinds, intent.areaId(), onPage).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Sponsored search slot skipped: {}", e.getMessage());
            return null;
        }
    }

    private com.bdreview.platform.promo.SponsoredService sponsoredService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSponsoredService(com.bdreview.platform.promo.SponsoredService sponsoredService) {
        this.sponsoredService = sponsoredService;
    }

    /** Everything criteria/notice/reason building needs about one request. */
    private record Context(SearchIntent intent, Request r, AreaRef area, boolean userCoords) {
        boolean priceInferred() {
            return intent.price() != PricePreference.NONE && r.priceTier() == null;
        }

        boolean areaInferred() {
            return area != null && r.areaId() == null;
        }

        boolean nearMeActive() {
            return intent.nearMe() && userCoords;
        }

        boolean locationInferred() {
            return r.areaId() == null && (area != null || intent.cityId() != null);
        }
    }

    private List<EnumSet<Relax>> applicableSteps(Context ctx) {
        EnumSet<Relax> applicable = EnumSet.noneOf(Relax.class);
        if (ctx.intent().openNow()) {
            applicable.add(Relax.OPEN_NOW);
        }
        if (ctx.priceInferred()) {
            applicable.add(Relax.PRICE);
        }
        if ((ctx.areaInferred() && ctx.area().centroidLat() != null) || ctx.nearMeActive()) {
            applicable.add(Relax.NEARBY);
        }
        if (ctx.locationInferred() || ctx.nearMeActive()) {
            applicable.add(Relax.ANYWHERE);
        }
        if (ctx.intent().hasItemConcepts() && ctx.r().categoryId() == null) {
            applicable.add(Relax.BROADER);
        }
        if (!ctx.intent().freeTerms().isEmpty()) {
            applicable.add(Relax.FUZZY);
        }
        Set<EnumSet<Relax>> steps = new LinkedHashSet<>();
        for (EnumSet<Relax> template : LADDER) {
            EnumSet<Relax> step = EnumSet.copyOf(template);
            step.retainAll(applicable);
            steps.add(step);
        }
        return new ArrayList<>(steps);
    }

    private Criteria criteria(Context ctx, EnumSet<Relax> step) {
        SearchIntent intent = ctx.intent();
        Request r = ctx.r();

        List<String> likeTerms = new ArrayList<>();
        List<String> wordTerms = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Concept c : intent.concepts()) {
            c.aliases().forEach(a -> addTerm(a, seen, likeTerms, wordTerms));
        }
        intent.freeTerms().forEach(t -> addTerm(t, seen, likeTerms, wordTerms));

        Set<String> kinds = new LinkedHashSet<>();
        for (Concept c : intent.concepts()) {
            if (c.type() == ConceptType.CATEGORY || step.contains(Relax.BROADER)) {
                kinds.add(c.kind());
            }
        }

        UUID areaId = r.areaId();
        UUID cityId = null;
        Double originLat = null;
        Double originLng = null;
        Double radius = null;
        boolean relaxedLocation = step.contains(Relax.NEARBY) || step.contains(Relax.ANYWHERE);
        if (areaId == null && ctx.area() != null && !relaxedLocation) {
            areaId = ctx.area().id();
        }
        if (areaId == null && ctx.area() == null && intent.cityId() != null && !step.contains(Relax.ANYWHERE)) {
            cityId = intent.cityId();
        }
        if (ctx.nearMeActive()) {
            originLat = r.lat();
            originLng = r.lng();
            radius = step.contains(Relax.ANYWHERE) ? null
                    : step.contains(Relax.NEARBY) ? NEAR_ME_WIDE_RADIUS_M : NEAR_ME_RADIUS_M;
        } else if (step.contains(Relax.NEARBY) && ctx.areaInferred() && !step.contains(Relax.ANYWHERE)) {
            originLat = ctx.area().centroidLat();
            originLng = ctx.area().centroidLng();
            radius = NEAR_AREA_RADIUS_M;
        } else if (ctx.userCoords()) {
            // Location was granted but not asked for: distance only nudges the ranking.
            originLat = r.lat();
            originLng = r.lng();
        }

        List<String> tiers = new ArrayList<>();
        List<String> softTiers = new ArrayList<>();
        if (r.priceTier() != null) {
            tiers.add(r.priceTier());
        } else if (intent.price() != PricePreference.NONE) {
            List<String> preferred = intent.price() == PricePreference.LOW
                    ? List.of("BUDGET") : List.of("EXPENSIVE", "VERY_EXPENSIVE");
            (step.contains(Relax.PRICE) ? softTiers : tiers).addAll(preferred);
        }

        String sort = switch (r.sort() == null ? "" : r.sort()) {
            case "rating", "most_reviewed" -> r.sort();
            case "distance" -> originLat != null ? "distance" : "relevance";
            default -> "relevance";
        };

        return new Criteria(likeTerms, wordTerms, List.copyOf(kinds), intent.hasWhat(),
                step.contains(Relax.FUZZY) ? FUZZY_NAME_THRESHOLD : SmartSearchRepository.NAME_MATCH_THRESHOLD,
                r.categoryId(), areaId, cityId, tiers, softTiers, r.minRating(),
                originLat, originLng, radius,
                intent.openNow() && !step.contains(Relax.OPEN_NOW),
                intent.ratingHigh() ? RATING_WEIGHT_BEST : RATING_WEIGHT, sort);
    }

    /**
     * Short Latin terms ("ac", "spa", "cha") would substring-match half the database, so 2-3 letter
     * Latin terms are matched as whole words instead; everything else (incl. all Bangla) as substrings.
     */
    private static void addTerm(String term, Set<String> seen, List<String> likeTerms, List<String> wordTerms) {
        if (term.length() < 2 || !seen.add(term)) {
            return;
        }
        if (term.length() >= 4 || SearchText.hasBengali(term)) {
            likeTerms.add(term);
        } else {
            wordTerms.add(term);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Explanations
    // ------------------------------------------------------------------------------------------

    private List<String> matchReasons(Context ctx, EnumSet<Relax> used, Hit h, String lang) {
        boolean bn = "bn".equals(lang);
        List<String> out = new ArrayList<>(2);
        Concept item = ctx.intent().concepts().stream()
                .filter(c -> c.type() == ConceptType.ITEM).findFirst().orElse(null);
        if (h.menuHit() != null) {
            out.add((bn ? "মেনুতে: " : "Menu: ") + h.menuHit());
        } else if (h.serviceHit() != null) {
            out.add((bn ? "সেবা: " : "Offers: ") + h.serviceHit());
        } else if (item != null && h.nameScore() >= SmartSearchRepository.NAME_MATCH_THRESHOLD) {
            out.add(bn ? item.label("bn") + " মিলেছে" : item.label("en") + " match");
        } else if (item != null && h.descHit()) {
            out.add(bn ? item.label("bn") + " উল্লেখ আছে" : "Mentions " + item.label("en").toLowerCase(Locale.ROOT));
        }
        if (used.contains(Relax.NEARBY) && !used.contains(Relax.ANYWHERE) && ctx.areaInferred()
                && !ctx.nearMeActive() && h.distanceMeters() != null) {
            String km = String.format(Locale.ROOT, "%.1f", h.distanceMeters() / 1000.0);
            out.add(bn ? placeBn(ctx.area().name()) + " থেকে " + km + " কিমি" : km + " km from " + ctx.area().name());
        }
        return out;
    }

    private SmartSearchResponse.IntentSummary summary(SearchIntent intent, AreaRef area, String lang) {
        List<SmartSearchResponse.IntentChip> what = intent.concepts().stream()
                .map(c -> new SmartSearchResponse.IntentChip(c.label(lang), c.icon())).toList();
        String location = intent.areaName() != null ? intent.areaName() : intent.cityName();
        if (location != null && "bn".equals(lang)) {
            location = placeBn(location);
        }
        boolean known = area != null || intent.cityId() != null;
        return new SmartSearchResponse.IntentSummary(what, intent.freeTerms(), location, location == null || known,
                intent.price() == PricePreference.NONE ? null : intent.price().name(),
                intent.ratingHigh(), intent.nearMe(), intent.openNow(), intent.correctedQuery());
    }

    private String notice(Context ctx, EnumSet<Relax> used, String lang) {
        String unknownArea = unknownAreaNotice(ctx, lang);
        if (used.isEmpty()) {
            return unknownArea;
        }
        String strict = describe(ctx, EnumSet.noneOf(Relax.class), lang);
        String relaxed = describe(ctx, used, lang);
        String sentence = "bn".equals(lang)
                ? "“" + strict + "” — হুবহু মিল পাওয়া যায়নি। দেখানো হচ্ছে: " + relaxed + "।"
                : "No exact matches for " + strict + ". Showing " + relaxed + " instead.";
        return unknownArea == null ? sentence : unknownArea + " " + sentence;
    }

    private String unknownAreaNotice(Context ctx, String lang) {
        SearchIntent intent = ctx.intent();
        if (intent.areaName() == null || intent.areaId() != null || ctx.r().areaId() != null) {
            return null;
        }
        return "bn".equals(lang)
                ? placeBn(intent.areaName()) + " এলাকায় এখনো কোনো ব্যবসা তালিকাভুক্ত নেই — সব এলাকার ফলাফল দেখানো হচ্ছে।"
                : "We don't have listings in " + intent.areaName() + " yet — showing results from all areas.";
    }

    /** "budget biryani in Mirpur" / "biryani near Mirpur (any price)" — what a given step actually searched for. */
    private String describe(Context ctx, EnumSet<Relax> step, String lang) {
        boolean bn = "bn".equals(lang);
        SearchIntent intent = ctx.intent();

        String price = null;
        PricePreference pref = intent.price();
        if (ctx.r().priceTier() == null && pref != PricePreference.NONE && !step.contains(Relax.PRICE)) {
            price = pref == PricePreference.LOW ? (bn ? "সাশ্রয়ী" : "budget") : (bn ? "প্রিমিয়াম" : "premium");
        }

        List<String> whatParts = new ArrayList<>();
        if (step.contains(Relax.BROADER)) {
            Set<String> kinds = new LinkedHashSet<>();
            intent.concepts().forEach(c -> kinds.add(c.kind()));
            for (String kind : kinds) {
                whatParts.add(categoryLabel(kind, lang));
            }
        } else {
            intent.concepts().forEach(c -> whatParts.add(bn ? c.label("bn") : c.label("en").toLowerCase(Locale.ROOT)));
        }
        if (!step.contains(Relax.BROADER) || intent.concepts().isEmpty()) {
            intent.freeTerms().forEach(t -> whatParts.add("“" + t + "”"));
        }
        if (step.contains(Relax.FUZZY)) {
            whatParts.add(bn ? "(কাছাকাছি নাম)" : "(similar names)");
        }
        String what = whatParts.isEmpty() ? (bn ? "ব্যবসা" : "businesses") : String.join(bn ? " ও " : " & ", whatParts);

        String where = null;
        if (ctx.nearMeActive()) {
            where = step.contains(Relax.ANYWHERE) ? (bn ? "সব এলাকায়" : "in all areas")
                    : step.contains(Relax.NEARBY) ? (bn ? "আপনার ১৫ কিমির মধ্যে" : "within 15 km of you")
                    : (bn ? "আপনার কাছে" : "near you");
        } else if (ctx.areaInferred()) {
            String name = bn ? placeBn(ctx.area().name()) : ctx.area().name();
            where = step.contains(Relax.ANYWHERE) ? (bn ? "সব এলাকায়" : "in all areas")
                    : step.contains(Relax.NEARBY) ? (bn ? name + "-এর কাছে" : "near " + name)
                    : (bn ? name + " এলাকায়" : "in " + name);
        } else if (intent.cityId() != null && ctx.r().areaId() == null) {
            String name = bn ? placeBn(intent.cityName()) : intent.cityName();
            where = step.contains(Relax.ANYWHERE) ? (bn ? "সব শহরে" : "in all cities") : (bn ? name + "-এ" : "in " + name);
        }

        String open = intent.openNow() && !step.contains(Relax.OPEN_NOW) ? (bn ? "এখন খোলা" : "open now") : null;
        String anyPrice = step.contains(Relax.PRICE) && ctx.priceInferred() ? (bn ? "(সব দামের)" : "(any price)") : null;

        List<String> parts = new ArrayList<>();
        if (bn) {
            add(parts, where);
            add(parts, open);
            add(parts, price);
            add(parts, what);
        } else {
            add(parts, price);
            add(parts, what);
            add(parts, where);
            add(parts, open);
        }
        add(parts, anyPrice);
        return String.join(" ", parts);
    }

    private static void add(List<String> parts, String s) {
        if (s != null && !s.isBlank()) {
            parts.add(s);
        }
    }

    /** "restaurants" for RESTAURANT — the CATEGORY concept's own label, falling back to the DB category name. */
    private String categoryLabel(String kind, String lang) {
        for (Concept c : lexicon.concepts()) {
            if (c.type() == ConceptType.CATEGORY && c.kind().equals(kind)) {
                return "bn".equals(lang) ? c.label("bn") : c.label("en").toLowerCase(Locale.ROOT);
            }
        }
        String dbName = referenceData.get().categoryNameByKind().get(kind);
        return dbName != null ? dbName : kind.toLowerCase(Locale.ROOT);
    }

    private String placeBn(String name) {
        SearchLexicon.Place p = lexicon.area(name);
        if (p == null) {
            p = lexicon.city(name);
        }
        return p != null ? p.bnName() : name;
    }

    // ------------------------------------------------------------------------------------------
    // Suggestions
    // ------------------------------------------------------------------------------------------

    static final int MAX_SUGGESTIONS = 8;

    public List<SearchSuggestion> suggest(String rawQuery, String rawLang) {
        String lang = "bn".equals(rawLang) ? "bn" : "en";
        boolean bn = "bn".equals(lang);
        String n = SearchText.normalize(rawQuery);
        SearchReferenceData.Snapshot ref = referenceData.get();
        Map<String, SearchSuggestion> out = new LinkedHashMap<>();

        if (n.isEmpty()) {
            popular(ref, lang).forEach(s -> out.putIfAbsent(s.query(), s));
            return List.copyOf(out.values());
        }

        List<String> tokens = SearchText.tokens(n);
        String last = tokens.get(tokens.size() - 1);
        String head = String.join(" ", tokens.subList(0, tokens.size() - 1));
        SearchIntent headIntent = head.isEmpty() ? null : parser.parse(head);

        // 1. Complete the last word(s) into a concept: "biri" → Biryani, "beauty p" → Beauty parlour.
        List<String[]> conceptHits = conceptCompletions(tokens);
        for (int i = 0; i < conceptHits.size() && i < 2; i++) {
            Concept c = lexicon.concept(conceptHits.get(i)[0]);
            String completedHead = conceptHits.get(i)[1];
            // Latin completions use the concept's canonical spelling ("biri" → "biryani", not whichever
            // transliteration happened to be shortest); Bangla keeps the Bangla word the user was typing.
            String completed = SearchText.hasBengali(conceptHits.get(i)[2]) ? conceptHits.get(i)[2] : c.aliases().get(0);
            String base = (completedHead.isEmpty() ? "" : completedHead + " ") + completed;
            String label = (completedHead.isEmpty() ? "" : completedHead + " ") + c.label(lang);
            put(out, new SearchSuggestion("CONCEPT", label, base, c.icon(), null, null));
            if (i == 0) {
                SearchIntent baseIntent = parser.parse(base);
                if (baseIntent.areaName() == null && !baseIntent.nearMe()) {
                    List<AreaRef> areas = ref.topAreasByKind().getOrDefault(c.kind(), ref.topAreas());
                    areas.stream().limit(2).forEach(a -> put(out, new SearchSuggestion("CONCEPT_AREA",
                            bn ? placeBn(a.name()) + " এলাকায় " + label : label + " in " + a.name(),
                            base + " " + a.name().toLowerCase(Locale.ROOT), "📍", null, null)));
                    put(out, new SearchSuggestion("CONCEPT_NEAR",
                            bn ? "আমার কাছে " + label : label + " near me",
                            base + " near me", "📍", null, null));
                }
            }
        }

        // 2. Complete the last word into an area: "biryani mir" → Biryani in Mirpur, "mir" → Mirpur.
        if (last.length() >= 2) {
            boolean headHasWhat = headIntent != null && headIntent.hasWhat();
            // "biryani" → "Biryani"; a head with words the lexicon doesn't know is shown as typed.
            String headLabel = headHasWhat && headIntent.freeTerms().isEmpty()
                    ? String.join(bn ? " ও " : " & ", headIntent.concepts().stream().map(c -> c.label(lang)).toList())
                    : head;
            for (String areaName : areaCompletions(last, ref)) {
                String q = (head.isEmpty() ? "" : head + " ") + areaName.toLowerCase(Locale.ROOT);
                String label = headHasWhat
                        ? (bn ? placeBn(areaName) + " এলাকায় " + headLabel : headLabel + " in " + areaName)
                        : (bn ? placeBn(areaName) : areaName);
                put(out, new SearchSuggestion(headHasWhat ? "CONCEPT_AREA" : "AREA", label, q, "📍", null, null));
                if (out.size() >= MAX_SUGGESTIONS) {
                    break;
                }
            }
        }

        // 3. Specific businesses by name — the only suggestion source that touches the database.
        if (n.length() >= 2 && out.size() < MAX_SUGGESTIONS) {
            for (SmartSearchRepository.NameHit hit : repository.businessNamesLike(n, 4)) {
                put(out, new SearchSuggestion("BUSINESS", hit.name(), hit.name(), null, hit.slug(), hit.areaName()));
            }
        }
        return out.values().stream().limit(MAX_SUGGESTIONS).toList();
    }

    private static void put(Map<String, SearchSuggestion> out, SearchSuggestion s) {
        out.putIfAbsent(s.type().equals("BUSINESS") ? "biz:" + s.slug() : s.query(), s);
    }

    /**
     * Each result is {conceptId, headBeforeCompletion, completedPhrase}. Tries completing the last two
     * words as one phrase first ("beauty p"), then the last word alone; exact alias hits rank first,
     * then shorter completions. Falls back to typo correction of a finished word ("biriyanni").
     */
    private List<String[]> conceptCompletions(List<String> tokens) {
        List<String[]> hits = new ArrayList<>();
        Set<String> seenConcepts = new LinkedHashSet<>();
        for (int tail = Math.min(2, tokens.size()); tail >= 1; tail--) {
            String prefix = String.join(" ", tokens.subList(tokens.size() - tail, tokens.size()));
            String head = String.join(" ", tokens.subList(0, tokens.size() - tail));
            if (prefix.length() < 2) {
                continue;
            }
            List<Map.Entry<String, SearchLexicon.Entry>> matches = lexicon.phrases().entrySet().stream()
                    .filter(e -> e.getValue().signal() == SearchLexicon.Signal.CONCEPT && e.getKey().startsWith(prefix))
                    .sorted((a, b) -> {
                        boolean ea = a.getKey().equals(prefix);
                        boolean eb = b.getKey().equals(prefix);
                        if (ea != eb) {
                            return ea ? -1 : 1;
                        }
                        return Integer.compare(a.getKey().length(), b.getKey().length());
                    })
                    .toList();
            for (Map.Entry<String, SearchLexicon.Entry> m : matches) {
                if (seenConcepts.add(m.getValue().value())) {
                    hits.add(new String[]{m.getValue().value(), head, m.getKey()});
                }
            }
            if (!hits.isEmpty()) {
                return hits;
            }
        }
        String last = tokens.get(tokens.size() - 1);
        String fixed = parser.typoCorrect(last);
        if (fixed != null) {
            SearchLexicon.Entry e = lexicon.lookup(fixed);
            if (e != null && e.signal() == SearchLexicon.Signal.CONCEPT) {
                hits.add(new String[]{e.value(), String.join(" ", tokens.subList(0, tokens.size() - 1)), fixed});
            }
        }
        return hits;
    }

    /** Areas with listings whose name (or a lexicon spelling of it) starts with `prefix`, busiest first. */
    private List<String> areaCompletions(String prefix, SearchReferenceData.Snapshot ref) {
        Set<String> names = new LinkedHashSet<>();
        for (AreaRef a : ref.topAreas()) {
            if (a.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(a.name());
            }
        }
        lexicon.phrases().forEach((phrase, e) -> {
            if (e.signal() == SearchLexicon.Signal.AREA && phrase.startsWith(prefix)) {
                AreaRef a = ref.areasByLowerName().get(e.value().toLowerCase(Locale.ROOT));
                if (a != null && a.businessCount() > 0) {
                    names.add(a.name());
                }
            }
        });
        return names.stream().limit(2).toList();
    }

    /**
     * Empty box: the category kinds that actually have listings, busiest first, then the busiest
     * area for the top two — derived from live counts, so it never suggests something with no results.
     */
    private List<SearchSuggestion> popular(SearchReferenceData.Snapshot ref, String lang) {
        boolean bn = "bn".equals(lang);
        List<Concept> categories = lexicon.concepts().stream()
                .filter(c -> c.type() == ConceptType.CATEGORY && ref.businessCountByKind().getOrDefault(c.kind(), 0) > 0)
                .sorted((a, b) -> Integer.compare(ref.businessCountByKind().getOrDefault(b.kind(), 0),
                        ref.businessCountByKind().getOrDefault(a.kind(), 0)))
                .toList();
        List<SearchSuggestion> out = new ArrayList<>();
        for (Concept c : categories) {
            out.add(new SearchSuggestion("POPULAR", c.label(lang), c.aliases().get(0), c.icon(), null, null));
        }
        for (Concept c : categories.subList(0, Math.min(2, categories.size()))) {
            List<AreaRef> areas = ref.topAreasByKind().getOrDefault(c.kind(), List.of());
            if (!areas.isEmpty()) {
                AreaRef a = areas.get(0);
                out.add(new SearchSuggestion("POPULAR",
                        bn ? placeBn(a.name()) + " এলাকায় " + c.label("bn") : c.label("en") + " in " + a.name(),
                        c.aliases().get(0) + " " + a.name().toLowerCase(Locale.ROOT), "📍", null, null));
            }
        }
        return out.stream().limit(6).toList();
    }
}
