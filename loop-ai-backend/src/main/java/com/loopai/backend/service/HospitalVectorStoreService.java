package com.loopai.backend.service;

import com.loopai.backend.model.Hospital;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Vector Store Service for semantic hospital search
 * Uses In-Memory store by default (can be extended to ChromaDB)
 * Combines vector similarity search with exact matching capabilities
 */
@Service
@Slf4j
public class HospitalVectorStoreService {

    private final CsvLoaderService csvLoaderService;
    private Map<String, Hospital> hospitalIndex = new HashMap<>(); // ID -> Hospital

    // City name aliases for common variations
    private static final Map<String, Set<String>> CITY_ALIASES = new HashMap<>();
    static {
        CITY_ALIASES.put("bengaluru", Set.of("bangalore", "bengaluru", "blr"));
        CITY_ALIASES.put("mumbai", Set.of("mumbai", "bombay"));
        CITY_ALIASES.put("chennai", Set.of("chennai", "madras"));
        CITY_ALIASES.put("kolkata", Set.of("kolkata", "calcutta"));
        CITY_ALIASES.put("delhi", Set.of("delhi", "new delhi", "newdelhi"));
        CITY_ALIASES.put("pune", Set.of("pune", "poona"));
        CITY_ALIASES.put("hyderabad", Set.of("hyderabad", "hyd"));
        CITY_ALIASES.put("gurugram", Set.of("gurugram", "gurgaon"));
        CITY_ALIASES.put("ghaziabad", Set.of("ghaziabad", "gzb"));
    }

    public HospitalVectorStoreService(CsvLoaderService csvLoaderService) {
        this.csvLoaderService = csvLoaderService;
    }

    /**
     * Check if two city names match (considering aliases)
     */
    private boolean citiesMatch(String city1, String city2) {
        if (city1 == null || city2 == null)
            return false;
        String c1 = city1.toLowerCase().trim();
        String c2 = city2.toLowerCase().trim();

        // Direct match
        if (c1.contains(c2) || c2.contains(c1))
            return true;

        // Check aliases
        for (Set<String> aliases : CITY_ALIASES.values()) {
            boolean c1InSet = aliases.stream().anyMatch(a -> c1.contains(a) || a.contains(c1));
            boolean c2InSet = aliases.stream().anyMatch(a -> c2.contains(a) || a.contains(c2));
            if (c1InSet && c2InSet)
                return true;
        }
        return false;
    }

    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("Initializing Hospital Vector Store");
        log.info("========================================");

        try {
            // Load hospitals into local index for fast exact-match lookup
            // Vector embeddings are DISABLED to avoid slow startup (2179 API calls)
            loadHospitalsToIndex();

            log.info(" Using Exact Match Search (fast startup)");
            log.info(" Hospital index initialized with {} hospitals", hospitalIndex.size());

        } catch (Exception e) {
            log.error("Failed to initialize Hospital Store: {}", e.getMessage());
        }

        log.info("========================================");
    }

    /**
     * Load hospitals into local index without generating embeddings
     */
    private void loadHospitalsToIndex() {
        List<Hospital> hospitals = csvLoaderService.getAllHospitals();

        if (hospitals.isEmpty()) {
            log.warn("⚠️ No hospitals to load");
            return;
        }

        hospitalIndex.clear();
        for (Hospital hospital : hospitals) {
            hospitalIndex.put(hospital.getId(), hospital);
        }

        log.info("📊 Loaded {} hospitals into index", hospitalIndex.size());
    }

    /**
     * Semantic search - falls back to keyword-based search
     * (Vector embeddings disabled for fast startup)
     */
    public List<Hospital> semanticSearch(String query, int maxResults) {
        log.info("🔍 Searching for: \"{}\"", query);

        // Use keyword-based search instead of vector embeddings
        // This is faster and doesn't require OpenAI API calls
        String lowerQuery = query.toLowerCase();

        List<Hospital> results = hospitalIndex.values().stream()
                .filter(h -> matchesKeywords(h, lowerQuery))
                .limit(maxResults)
                .collect(Collectors.toList());

        log.info("  Found {} matches", results.size());
        return results;
    }

    /**
     * Keyword-based matching for search queries
     */
    private boolean matchesKeywords(Hospital h, String query) {
        // Check if any hospital field contains words from the query
        String hospitalText = String.join(" ",
                h.getName() != null ? h.getName().toLowerCase() : "",
                h.getCity() != null ? h.getCity().toLowerCase() : "",
                h.getAddress() != null ? h.getAddress().toLowerCase() : "",
                h.getSpecialties() != null ? h.getSpecialties().toLowerCase() : "");

        // Match if hospital contains any significant word from query
        String[] queryWords = query.split("\\s+");
        for (String word : queryWords) {
            if (word.length() > 2 && hospitalText.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Exact match search on hospital fields (supports city aliases)
     */
    public List<Hospital> exactSearch(String name, String city, int maxResults) {
        log.info("🎯 Exact search - name: \"{}\", city: \"{}\"", name, city);

        String[] keywords = name != null ? name.toLowerCase().split("[\\s,]+") : new String[0];

        return hospitalIndex.values().stream()
                .filter(h -> {
                    // City match
                    if (city != null && !city.isEmpty() && !citiesMatch(h.getCity(), city)) {
                        return false;
                    }

                    // Keywords match in name or address
                    String nameAndAddress = ((h.getName() != null ? h.getName() : "") + " " +
                            (h.getAddress() != null ? h.getAddress() : "")).toLowerCase();

                    for (String keyword : keywords) {
                        if (keyword.length() > 2 && !nameAndAddress.contains(keyword)) {
                            return false;
                        }
                    }
                    return true;
                })
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * Search hospitals by city only (supports city aliases)
     */
    public List<Hospital> searchByCity(String city, int maxResults) {
        log.info("🏙️ Search by city: \"{}\"", city);

        return hospitalIndex.values().stream()
                .filter(h -> citiesMatch(h.getCity(), city))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * Check if a specific hospital exists in a city (for confirmation queries)
     * Now searches in BOTH name AND address for better matching
     * Handles city aliases (Bangalore/Bengaluru, Mumbai/Bombay, etc.)
     */
    public Optional<Hospital> findExactHospital(String hospitalName, String city) {
        log.info("Finding hospital: \"{}\" in \"{}\"", hospitalName, city);

        // Split query into keywords for flexible matching
        String[] keywords = hospitalName.toLowerCase().split("[\\s,]+");

        return hospitalIndex.values().stream()
                .filter(h -> {
                    // City match (using alias support)
                    boolean cityMatch = city == null || city.isEmpty() ||
                            city.equalsIgnoreCase("unknown") ||
                            citiesMatch(h.getCity(), city);

                    if (!cityMatch)
                        return false;

                    // Check if ALL keywords match in name OR address
                    String nameAndAddress = ((h.getName() != null ? h.getName() : "") + " " +
                            (h.getAddress() != null ? h.getAddress() : "")).toLowerCase();

                    for (String keyword : keywords) {
                        if (keyword.length() > 2 && !nameAndAddress.contains(keyword)) {
                            return false;
                        }
                    }
                    return true;
                })
                .findFirst();
    }

    /**
     * Find hospitals matching name/keywords across multiple cities
     * Searches in both name AND address fields
     */
    public Map<String, List<Hospital>> findHospitalsByNameGroupedByCity(String hospitalName) {
        log.info("Finding hospitals by name grouped by city: \"{}\"", hospitalName);

        String[] keywords = hospitalName.toLowerCase().split("[\\s,]+");

        return hospitalIndex.values().stream()
                .filter(h -> {
                    String nameAndAddress = ((h.getName() != null ? h.getName() : "") + " " +
                            (h.getAddress() != null ? h.getAddress() : "")).toLowerCase();

                    for (String keyword : keywords) {
                        if (keyword.length() > 2 && !nameAndAddress.contains(keyword)) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.groupingBy(
                        h -> h.getCity() != null ? h.getCity() : "Unknown",
                        Collectors.toList()));
    }

    /**
     * Get count of indexed hospitals
     */
    public int getIndexedCount() {
        return hospitalIndex.size();
    }

    /**
     * Check if vector search is enabled (currently disabled for fast startup)
     */
    public boolean isVectorSearchEnabled() {
        return false; // Vector embeddings disabled
    }

    /**
     * Get all unique cities in the database
     */
    public Set<String> getAllCities() {
        return hospitalIndex.values().stream()
                .map(Hospital::getCity)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Reload hospitals from CSV
     */
    public void reload() {
        log.info("🔄 Reloading hospital data...");
        csvLoaderService.reload();
        loadHospitalsToIndex();
    }
}
