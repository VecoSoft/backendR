package com.bdreview.platform.promo;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.catalog.MenuItem;
import com.bdreview.platform.catalog.MenuItemRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.RateLimitExceededException;
import com.bdreview.platform.offer.Offer;
import com.bdreview.platform.offer.OfferRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * "Write for me" captions (V58). The browser never talks to the ML service: this service builds
 * the request from the business's REAL data only (name, category, area, the linked offer / menu
 * item / event), calls POST /promo/captions with a short timeout, re-validates what comes back
 * (length, emoji/hashtag limits, no superlative claims, no numbers that aren't in the data), and
 * falls back to template captions whenever the ML service is off, slow, failing or returns
 * something invalid. Quota: {@code captionsPerBusinessPerDay} generations per business per day.
 */
@Service
public class PromoCaptionService {

    private static final Logger log = LoggerFactory.getLogger(PromoCaptionService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    static final Duration TIMEOUT = Duration.ofSeconds(8);
    static final int MAX_LENGTH = 220;
    static final int MAX_EMOJIS = 2;
    static final int MAX_HASHTAGS = 3;
    /** Claims the business can't back up — rejected in any language. */
    static final List<String> FORBIDDEN_CLAIMS = List.of("best in", "no.1", "no 1", "number one", "#1", "the best",
            "guaranteed", "cheapest", "সেরা", "এক নম্বর", "১ নম্বর", "গ্যারান্টি", "সবচেয়ে সস্তা");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    public record CaptionRequest(@NotNull PromoEnums.BusinessPostType type, UUID offerId, UUID menuItemId,
                                 String eventTitle, Instant eventStart, String tone) {
    }

    /** {@code source}: AI when the ML service produced them, TEMPLATE for the fallback. */
    public record CaptionResult(List<String> bn, List<String> en, String source, int remainingToday) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MlRequest(String businessName, String category, String area, String type, Map<String, Object> offer,
                     Map<String, Object> menuItem, Map<String, Object> event, String tone, String language) {
    }

    record MlResponse(List<String> captions) {
    }

    private final PromoAccess access;
    private final BusinessRepository businessRepository;
    private final OfferRepository offerRepository;
    private final MenuItemRepository menuItemRepository;
    private final WebClient ml;
    private final JdbcTemplate jdbc;

    public PromoCaptionService(PromoAccess access, BusinessRepository businessRepository, OfferRepository offerRepository,
                               MenuItemRepository menuItemRepository, WebClient mlServiceWebClient, JdbcTemplate jdbc) {
        this.access = access;
        this.businessRepository = businessRepository;
        this.offerRepository = offerRepository;
        this.menuItemRepository = menuItemRepository;
        this.ml = mlServiceWebClient;
        this.jdbc = jdbc;
    }

    public CaptionResult captions(UUID userId, UUID businessId, CaptionRequest req) {
        access.requireCanPromote(userId, businessId);
        Business b = businessRepository.findAllByIdInWithPlace(List.of(businessId)).get(0);
        int limit = access.settings().getCaptionsPerBusinessPerDay();
        int used = reserveQuota(businessId, limit);
        int remaining = Math.max(0, limit - used);
        String tone = Set.of("friendly", "premium", "urgent").contains(req.tone()) ? req.tone() : "friendly";

        Offer offer = req.offerId() == null ? null : offerRepository.findById(req.offerId())
                .filter(o -> o.getBusinessId().equals(businessId)).orElseThrow(() -> new BadRequestException("Offer not found."));
        MenuItem item = req.menuItemId() == null ? null : menuItemRepository.findById(req.menuItemId())
                .filter(m -> m.getBusinessId().equals(businessId)).orElseThrow(() -> new BadRequestException("Menu item not found."));
        Facts facts = new Facts(b, req.type(), offer, item, req.eventTitle(), req.eventStart());

        if (access.settings().isCaptionAiEnabled()) {
            List<String> bn = callMl(facts, tone, "bn");
            List<String> en = bn.size() == 3 ? callMl(facts, tone, "en") : List.of();
            if (bn.size() == 3 && en.size() == 3) {
                return new CaptionResult(bn, en, "AI", remaining);
            }
        }
        return new CaptionResult(fallback(facts, "bn"), fallback(facts, "en"), "TEMPLATE", remaining);
    }

    /** Atomically counts one generation; throws once the day's quota is used up. Returns the count after this call. */
    private int reserveQuota(UUID businessId, int limit) {
        LocalDate today = LocalDate.now(ZONE);
        Integer used = jdbc.queryForObject("""
                INSERT INTO promo_caption_usage (business_id, day, used) VALUES (?, ?, 1)
                ON CONFLICT (business_id, day) DO UPDATE SET used = promo_caption_usage.used + 1
                RETURNING used
                """, Integer.class, businessId, today);
        if (used != null && used > limit) {
            throw new RateLimitExceededException("You've used today's " + limit + " caption suggestions. Try again tomorrow.");
        }
        return used == null ? 1 : used;
    }

    record Facts(Business business, PromoEnums.BusinessPostType type, Offer offer, MenuItem item, String eventTitle,
                 Instant eventStart) {
        String area() {
            return business.getArea() == null ? null : business.getArea().getName();
        }

        /** Every number a caption may mention — anything else would be an invented price or date. */
        Set<String> allowedNumbers() {
            Set<String> out = new HashSet<>();
            List<Object> sources = new ArrayList<>();
            sources.add(business.getName());
            if (offer != null) {
                sources.addAll(Arrays.asList(offer.getTitle(), offer.getDiscountValue(), offer.getOriginalPrice(),
                        offer.getOfferPrice(), offer.getValidUntil() == null ? null : date(offer.getValidUntil(), "d MMM")));
                sources.add("1 2"); // "Buy 1 Get 1", "2 for 1"
            }
            if (item != null) {
                sources.addAll(Arrays.asList(item.getName(), item.getPrice(), item.getPriceText()));
            }
            if (eventTitle != null) {
                sources.add(eventTitle);
            }
            if (eventStart != null) {
                sources.add(date(eventStart, "d MMM h:mm"));
            }
            for (Object s : sources) {
                if (s == null) {
                    continue;
                }
                String text = s instanceof BigDecimal bd ? bd.stripTrailingZeros().toPlainString() : s.toString();
                DIGITS.matcher(toLatinDigits(text)).results().forEach(m -> out.add(m.group()));
            }
            return out;
        }
    }

    private List<String> callMl(Facts f, String tone, String language) {
        Map<String, Object> offer = null;
        if (f.offer() != null) {
            offer = new LinkedHashMap<>();
            offer.put("title", f.offer().getTitle());
            offer.put("offerType", f.offer().getOfferType() == null ? null : f.offer().getOfferType().name());
            offer.put("discountValue", f.offer().getDiscountValue());
            offer.put("originalPrice", f.offer().getOriginalPrice());
            offer.put("offerPrice", f.offer().getOfferPrice());
            offer.put("validUntil", f.offer().getValidUntil() == null ? null : date(f.offer().getValidUntil(), "d MMM"));
        }
        Map<String, Object> menu = null;
        if (f.item() != null) {
            menu = new LinkedHashMap<>();
            menu.put("name", f.item().getName());
            if (f.item().getPrice() != null) {
                menu.put("price", f.item().getPrice());
            }
        }
        Map<String, Object> event = null;
        if (f.eventTitle() != null || f.eventStart() != null) {
            event = new LinkedHashMap<>();
            if (f.eventTitle() != null) {
                event.put("title", f.eventTitle());
            }
            if (f.eventStart() != null) {
                event.put("start", date(f.eventStart(), "d MMM h:mm a"));
            }
        }
        MlRequest body = new MlRequest(f.business().getName(),
                f.business().getCategory() == null ? null : f.business().getCategory().getName(), f.area(),
                f.type().name(), offer, menu, event, tone, language);
        try {
            MlResponse res = ml.post().uri("/promo/captions").bodyValue(body).retrieve()
                    .bodyToMono(MlResponse.class).block(TIMEOUT);
            if (res == null || res.captions() == null) {
                return List.of();
            }
            Set<String> allowed = f.allowedNumbers();
            List<String> valid = res.captions().stream().filter(Objects::nonNull).map(String::strip)
                    .filter(c -> isValid(c, allowed)).distinct().limit(3).toList();
            if (valid.size() < 3) {
                log.info("ML captions rejected by validation ({} of {} kept) — using template fallback", valid.size(),
                        res.captions().size());
            }
            return valid;
        } catch (Exception e) {
            log.warn("ML caption service unavailable ({}); using template captions", e.getMessage());
            return List.of();
        }
    }

    static boolean isValid(String caption, Set<String> allowedNumbers) {
        if (caption.isBlank() || caption.codePointCount(0, caption.length()) > MAX_LENGTH) {
            return false;
        }
        if (emojiCount(caption) > MAX_EMOJIS || caption.chars().filter(ch -> ch == '#').count() > MAX_HASHTAGS) {
            return false;
        }
        String lower = caption.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_CLAIMS.stream().anyMatch(lower::contains)) {
            return false;
        }
        return DIGITS.matcher(toLatinDigits(caption)).results().allMatch(m -> allowedNumbers.contains(m.group()));
    }

    static int emojiCount(String s) {
        return (int) s.codePoints().filter(cp -> cp >= 0x1F000 && cp <= 0x1FAFF || cp >= 0x2600 && cp <= 0x27BF).count();
    }

    static String toLatinDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> sb.appendCodePoint(cp >= '০' && cp <= '৯' ? '0' + (cp - '০') : cp));
        return sb.toString();
    }

    private static String date(Instant i, String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).withZone(ZONE).format(i);
    }

    /** Honest, data-only captions used whenever the AI isn't available. Three per language. */
    static List<String> fallback(Facts f, String lang) {
        String name = f.business().getName();
        String area = f.area();
        boolean bn = lang.equals("bn");
        String where = area == null ? "" : (bn ? " — " + area : " in " + area);
        String tag = "#" + name.replaceAll("[^\\p{L}\\p{N}]", "");
        List<String> out = new ArrayList<>();
        switch (f.type()) {
            case OFFER -> {
                String title = f.offer() == null ? "" : f.offer().getTitle();
                String until = f.offer() == null || f.offer().getValidUntil() == null ? "" : date(f.offer().getValidUntil(), "d MMM");
                out.add(bn ? name + "-এ চলছে: " + title + (until.isEmpty() ? "" : " (" + until + " পর্যন্ত)") + "। Jachai-তে অফারটি নিন! 🎉"
                        : title + " at " + name + (until.isEmpty() ? "" : " — valid until " + until) + ". Grab it on Jachai! 🎉");
                out.add(bn ? title + " — শুধু " + name + where + "। অফার শেষ হওয়ার আগেই চলে আসুন। " + tag
                        : "Don't miss " + title + " at " + name + where + ". Claim it before it ends. " + tag);
                out.add(bn ? "নতুন অফার! " + title + "। বিস্তারিত দেখুন Jachai-তে। #JachaiOffer"
                        : "New offer: " + title + ". See the details on Jachai. #JachaiOffer");
            }
            case MENU_ITEM -> {
                String item = f.item() == null ? "" : f.item().getName();
                String price = f.item() == null || f.item().getPrice() == null ? ""
                        : "৳" + f.item().getPrice().stripTrailingZeros().toPlainString();
                out.add(bn ? name + "-এর " + item + (price.isEmpty() ? "" : " " + price) + " — আজই অর্ডার করুন Jachai-তে! 😋"
                        : "Craving " + item + "? Order it from " + name + (price.isEmpty() ? "" : " for " + price) + " on Jachai! 😋");
                out.add(bn ? item + " এখন পাওয়া যাচ্ছে " + name + where + "। " + tag
                        : item + " is on the menu at " + name + where + ". " + tag);
                out.add(bn ? "আজকের পছন্দ: " + item + "। " + name + " থেকে সরাসরি অর্ডার করুন। #Jachai"
                        : "Today's pick: " + item + ". Order straight from " + name + ". #Jachai");
            }
            case EVENT -> {
                String title = f.eventTitle() == null ? name : f.eventTitle();
                String when = f.eventStart() == null ? "" : date(f.eventStart(), "d MMM, h:mm a");
                out.add(bn ? title + " — " + name + where + (when.isEmpty() ? "" : ", " + when) + "। আসবেন তো? 🎈"
                        : title + " at " + name + where + (when.isEmpty() ? "" : ", " + when) + ". Will you be there? 🎈");
                out.add(bn ? "ক্যালেন্ডারে লিখে রাখুন: " + title + (when.isEmpty() ? "" : " (" + when + ")") + "। " + tag
                        : "Save the date: " + title + (when.isEmpty() ? "" : " on " + when) + ". " + tag);
                out.add(bn ? name + "-এ আসছে " + title + "। আগ্রহী হলে Jachai-তে জানিয়ে দিন!"
                        : "Coming up at " + name + ": " + title + ". Tap Interested on Jachai!");
            }
            default -> {
                out.add(bn ? name + where + " — Jachai-তে আমাদের দেখুন, রিভিউ পড়ুন আর যোগাযোগ করুন। 👋"
                        : "Visit " + name + where + " — see us on Jachai, read reviews and get in touch. 👋");
                out.add(bn ? name + "-এর খবর: নতুন আপডেট দেখতে আমাদের Jachai পেজে চোখ রাখুন। " + tag
                        : "News from " + name + ": follow our Jachai page for updates. " + tag);
                out.add(bn ? "আপনার এলাকার " + name + " — এখন Jachai-তে। #Jachai"
                        : name + ", right in your neighbourhood — now on Jachai. #Jachai");
            }
        }
        return out.stream().map(c -> c.codePointCount(0, c.length()) > MAX_LENGTH
                ? c.substring(0, c.offsetByCodePoints(0, MAX_LENGTH - 1)) + "…" : c).toList();
    }
}
