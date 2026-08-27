package com.bdreview.platform.business;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Read-only lookup data for listing-creation forms and search filters. */
@RestController
@RequestMapping("/api/v1")
public class ReferenceDataController {

    private final CityRepository cityRepository;
    private final AreaRepository areaRepository;
    private final CategoryRepository categoryRepository;
    private final BusinessAttributeRepository attributeRepository;

    public ReferenceDataController(CityRepository cityRepository, AreaRepository areaRepository,
                                    CategoryRepository categoryRepository,
                                    BusinessAttributeRepository attributeRepository) {
        this.cityRepository = cityRepository;
        this.areaRepository = areaRepository;
        this.categoryRepository = categoryRepository;
        this.attributeRepository = attributeRepository;
    }

    @GetMapping("/cities")
    public ResponseEntity<List<City>> cities() {
        return ResponseEntity.ok(cityRepository.findAll());
    }

    @GetMapping("/cities/{cityId}/areas")
    public ResponseEntity<List<Area>> areas(@PathVariable UUID cityId) {
        return ResponseEntity.ok(areaRepository.findByCityId(cityId));
    }

    @GetMapping("/categories")
    public ResponseEntity<List<Category>> categories() {
        return ResponseEntity.ok(categoryRepository.findAll(Sort.by("name")));
    }

    @GetMapping("/attributes")
    public ResponseEntity<List<BusinessAttribute>> attributes() {
        return ResponseEntity.ok(attributeRepository.findAll());
    }
}
