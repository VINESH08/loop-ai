package com.loopai.backend.service;

import com.loopai.backend.model.Hospital;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Vector Store Service for semantic hospital search using ChromaDB
 * 
 * Architecture:
 * 1. CSV Data → Hospital Objects
 * 2. Hospital Text → Embeddings (using AllMiniLmL6V2 model - runs locally!)
 * 3. Embeddings → ChromaDB (vector database)
 * 4. User Query → Query Embedding → Similarity Search in ChromaDB
 * 
 * This is TRUE RAG - Retrieval Augmented Generation with vector similarity!
 */
@Service
@Slf4j
public class HospitalVectorStoreService {

    private final CsvLoaderService csvLoaderService;

    @Value("${chroma.host:localhost}")
    private String chromaHost;

    @Value("${chroma.port:8000}")
    private int chromaPort;

    @Value("${chroma.collection:hospitals}")
    private String chromaCollection;

    @Value("${vector.search.enabled:true}")
    private boolean vectorSearchEnabled;

    // Embedding model - runs locally, no API calls!
    private EmbeddingModel embeddingModel;

    // ChromaDB vector store
    private EmbeddingStore<TextSegment> embeddingStore;

    // Fallback index for exact matches
    private Map<String, Hospital> hospitalIndex = new HashMap<>();

    // City name aliases
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

    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("Initializing Hospital Vector Store");
        log.info("========================================");

        try {
            // Initialize local embedding model (AllMiniLmL6V2 - runs on CPU, no API!)
            log.info("Loading embedding model (AllMiniLmL6V2)...");
            this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
            log.info("Embedding model loaded successfully");

            // Try to connect to ChromaDB
            if (vectorSearchEnabled) {
                try {
                    initializeChromaDB();
                } catch (Exception e) {
                    log.warn("ChromaDB not available: {}. Using in-memory fallback.", e.getMessage());
                    vectorSearchEnabled = false;
                }
            }

            // Load hospitals
            loadHospitals();

        } catch (Exception e) {
            log.error("Failed to initialize Hospital Store: {}", e.getMessage(), e);
            vectorSearchEnabled = false;
        }

        log.info("========================================");
        log.info("Vector Search Enabled: {}", vectorSearchEnabled);
        log.info("Hospital count: {}", hospitalIndex.size());
        log.info("========================================");
    }

    /**
     * Initialize ChromaDB connection
     */
    private void initializeChromaDB() {
        String chromaUrl = "http://" + chromaHost + ":" + chromaPort;
        log.info("Connecting to ChromaDB at: {}", chromaUrl);

        this.embeddingStore = ChromaEmbeddingStore.builder()
                .baseUrl(chromaUrl)
                .collectionName(chromaCollection)
                .build();

        log.info("ChromaDB connected! Collection: {}", chromaCollection);
    }

    /**
     * Load hospitals from CSV and index them
     */
    private void loadHospitals() {
        List<Hospital> hospitals = csvLoaderService.getAllHospitals();

        if (hospitals.isEmpty()) {
            log.warn("No hospitals to load");
            return;
        }

        // Always build the HashMap index for exact matches
        hospitalIndex.clear();
        for (Hospital hospital : hospitals) {
            hospitalIndex.put(hospital.getId(), hospital);
        }
        log.info("Loaded {} hospitals into HashMap index", hospitalIndex.size());

        // If vector search is enabled, generate embeddings and store in ChromaDB
        if (vectorSearchEnabled && embeddingStore != null) {
            log.info("Generating embeddings and storing in ChromaDB...");
            indexHospitalsInChroma(hospitals);
        }
    }

    /**
     * Generate embeddings for all hospitals and store in ChromaDB
     */
    private void indexHospitalsInChroma(List<Hospital> hospitals) {
        long startTime = System.currentTimeMillis();
        int batchSize = 100;
        int indexed = 0;

        for (int i = 0; i < hospitals.size(); i += batchSize) {
            List<Hospital> batch = hospitals.subList(i, Math.min(i + batchSize, hospitals.size()));

            List<TextSegment> segments = new ArrayList<>();
            List<Embedding> embeddings = new ArrayList<>();

            for (Hospital h : batch) {
                // Create searchable text for embedding
                String text = h.toSearchableText();

                // Create metadata for filtering
                Metadata metadata = Metadata.from(Map.of(
                        "id", h.getId(),
                        "name", h.getName() != null ? h.getName() : "",
                        "city", h.getCity() != null ? h.getCity().toLowerCase() : "",
                        "address", h.getAddress() != null ? h.getAddress() : ""));

                TextSegment segment = TextSegment.from(text, metadata);
                segments.add(segment);

                // Generate embedding using local model
                Embedding embedding = embeddingModel.embed(text).content();
                embeddings.add(embedding);
            }

            // Store batch in ChromaDB
            embeddingStore.addAll(embeddings, segments);
            indexed += batch.size();

            if (indexed % 500 == 0 || indexed == hospitals.size()) {
                log.info("Indexed {}/{} hospitals in ChromaDB", indexed, hospitals.size());
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("ChromaDB indexing complete! {} hospitals in {}ms", indexed, duration);
    }

    /**
     * SEMANTIC SEARCH using Vector Similarity (ChromaDB)
     * This is the core RAG functionality!
     */
    public List<Hospital> semanticSearch(String query, int maxResults) {
        log.info("========================================");
        log.info("VECTOR SEARCH: \"{}\"", query);
        log.info("========================================");

        long startTime = System.currentTimeMillis();

        if (!vectorSearchEnabled || embeddingStore == null) {
            log.info("Vector search disabled, using keyword fallback");
            return keywordSearch(query, maxResults);
        }

        try {
            // Step 1: Convert query to embedding vector
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            log.info("Query embedded: {} dimensions", queryEmbedding.vector().length);

            // Step 2: Search ChromaDB for similar vectors
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(0.3) // Minimum similarity threshold
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

            log.info("ChromaDB returned {} matches", matches.size());

            // Step 3: Convert matches back to Hospital objects
            List<Hospital> results = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                String hospitalId = match.embedded().metadata().getString("id");
                Hospital hospital = hospitalIndex.get(hospitalId);
                if (hospital != null) {
                    results.add(hospital);
                    log.info("  Match: {} (score: {:.3f})", hospital.getName(), match.score());
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Vector search completed in {}ms, found {} results", duration, results.size());

            return results;

        } catch (Exception e) {
            log.error("Vector search failed: {}, falling back to keyword search", e.getMessage());
            return keywordSearch(query, maxResults);
        }
    }

    /**
     * SEMANTIC SEARCH with City Filter
     * Combines vector similarity with metadata filtering
     */
    public List<Hospital> semanticSearchWithCity(String query, String city, int maxResults) {
        log.info("========================================");
        log.info("VECTOR SEARCH WITH CITY FILTER");
        log.info("  Query: \"{}\"", query);
        log.info("  City: \"{}\"", city);
        log.info("========================================");

        if (!vectorSearchEnabled || embeddingStore == null) {
            return exactSearch(query, city, maxResults);
        }

        try {
            // Embed the query
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            // Search with more results then filter by city
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults * 3) // Get more for filtering
                    .minScore(0.3)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

            // Filter by city
            String normalizedCity = city != null ? city.toLowerCase() : "";
            List<Hospital> results = searchResult.matches().stream()
                    .filter(match -> {
                        String matchCity = match.embedded().metadata().getString("city");
                        return citiesMatch(matchCity, normalizedCity);
                    })
                    .map(match -> hospitalIndex.get(match.embedded().metadata().getString("id")))
                    .filter(Objects::nonNull)
                    .limit(maxResults)
                    .collect(Collectors.toList());

            log.info("Found {} hospitals matching query in {}", results.size(), city);
            return results;

        } catch (Exception e) {
            log.error("Vector search with city failed: {}", e.getMessage());
            return exactSearch(query, city, maxResults);
        }
    }

    /**
     * Keyword-based search fallback
     */
    private List<Hospital> keywordSearch(String query, int maxResults) {
        String lowerQuery = query.toLowerCase();

        return hospitalIndex.values().stream()
                .filter(h -> matchesKeywords(h, lowerQuery))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    private boolean matchesKeywords(Hospital h, String query) {
        String hospitalText = String.join(" ",
                h.getName() != null ? h.getName().toLowerCase() : "",
                h.getCity() != null ? h.getCity().toLowerCase() : "",
                h.getAddress() != null ? h.getAddress().toLowerCase() : "",
                h.getSpecialties() != null ? h.getSpecialties().toLowerCase() : "");

        String[] queryWords = query.split("\\s+");
        for (String word : queryWords) {
            if (word.length() > 2 && hospitalText.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if two city names match (considering aliases)
     */
    private boolean citiesMatch(String city1, String city2) {
        if (city1 == null || city2 == null)
            return false;
        String c1 = city1.toLowerCase().trim();
        String c2 = city2.toLowerCase().trim();

        if (c1.contains(c2) || c2.contains(c1))
            return true;

        for (Set<String> aliases : CITY_ALIASES.values()) {
            boolean c1InSet = aliases.stream().anyMatch(a -> c1.contains(a) || a.contains(c1));
            boolean c2InSet = aliases.stream().anyMatch(a -> c2.contains(a) || a.contains(c2));
            if (c1InSet && c2InSet)
                return true;
        }
        return false;
    }

    /**
     * Exact match search (HashMap-based, for confirmation queries)
     */
    public List<Hospital> exactSearch(String name, String city, int maxResults) {
        log.info("Exact search - name: \"{}\", city: \"{}\"", name, city);

        String[] keywords = name != null ? name.toLowerCase().split("[\\s,]+") : new String[0];

        return hospitalIndex.values().stream()
                .filter(h -> {
                    if (city != null && !city.isEmpty() && !citiesMatch(h.getCity(), city)) {
                        return false;
                    }

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
     * Search hospitals by city only
     */
    public List<Hospital> searchByCity(String city, int maxResults) {
        log.info("Search by city: \"{}\"", city);

        return hospitalIndex.values().stream()
                .filter(h -> citiesMatch(h.getCity(), city))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * Find exact hospital (for confirmation queries)
     */
    public Optional<Hospital> findExactHospital(String hospitalName, String city) {
        log.info("Finding hospital: \"{}\" in \"{}\"", hospitalName, city);

        String[] keywords = hospitalName.toLowerCase().split("[\\s,]+");

        return hospitalIndex.values().stream()
                .filter(h -> {
                    boolean cityMatch = city == null || city.isEmpty() ||
                            city.equalsIgnoreCase("unknown") ||
                            citiesMatch(h.getCity(), city);

                    if (!cityMatch)
                        return false;

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
     * Find hospitals by name grouped by city
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
     * Check if vector search is enabled
     */
    public boolean isVectorSearchEnabled() {
        return vectorSearchEnabled;
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
     * Reload hospitals from CSV and re-index in ChromaDB
     */
    public void reload() {
        log.info("Reloading hospital data...");
        csvLoaderService.reload();
        loadHospitals();
    }
}
