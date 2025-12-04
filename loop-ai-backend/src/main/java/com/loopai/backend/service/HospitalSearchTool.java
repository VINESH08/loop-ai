package com.loopai.backend.service;

import com.loopai.backend.model.Hospital;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RAG-enabled Hospital Search Tools
 * Uses ChromaDB vector store for semantic search + exact matching
 * Handles clarifying questions when data is ambiguous
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HospitalSearchTool {

    private final HospitalVectorStoreService vectorStoreService;

    @Tool("Search for excat hospitals dont go with semantic search")
    public String searchHospitals(
            @P("The search query describing what kind of excat hospital or service the user is looking for") String query,
            @P("Optional: specific city to filter results. Leave empty for all cities") String city,
            @P("Maximum number of results to return, default 5") int maxResults) {

        long toolStart = System.currentTimeMillis();

        log.info("========================================");
        log.info("🔍 RAG TOOL: searchHospitals (VECTOR SIMILARITY)");
        log.info("   Query: \"{}\"", query);
        log.info("   City: \"{}\"", city);
        log.info("   Max Results: {}", maxResults);
        log.info("   Vector Search Enabled: {}", vectorStoreService.isVectorSearchEnabled());
        log.info("========================================");

        if (maxResults <= 0)
            maxResults = 5;

        List<Hospital> results;
        long ragStart = System.currentTimeMillis();

        // If city is specified, use vector search with city filter
        if (city != null && !city.isEmpty()) {
            // Use semantic search with city filtering
            results = vectorStoreService.semanticSearchWithCity(query, city, maxResults);
        } else {
            // Pure semantic search - finds similar meanings!
            results = vectorStoreService.semanticSearch(query, maxResults);
        }

        long ragTime = System.currentTimeMillis() - ragStart;
        long totalToolTime = System.currentTimeMillis() - toolStart;

        log.info("⏱️ LOG TIME: RAG Vector Search = {}ms", ragTime);
        log.info("⏱️ LOG TIME: Tool Total = {}ms", totalToolTime);

        if (results.isEmpty()) {
            return buildNoResultsResponse(query, city);
        }

        return buildSearchResultsResponse(results, query, city);
    }

    @Tool("Find which city a hospital is in. ONLY use when user explicitly asks 'Which city is X in?' or 'In which city is X located?' - NOT for address requests.")
    public String findHospitalLocation(
            @P("The name of the hospital to find") String hospitalName) {

        log.info("========================================");
        log.info("📍 RAG TOOL: findHospitalLocation");
        log.info("   Hospital: \"{}\"", hospitalName);
        log.info("========================================");

        Map<String, List<Hospital>> hospitalsByCity = vectorStoreService
                .findHospitalsByNameGroupedByCity(hospitalName);

        if (hospitalsByCity.isEmpty()) {
            return String.format("I dont have '%s' in my database.", hospitalName);
        }

        if (hospitalsByCity.size() == 1) {
            String city = hospitalsByCity.keySet().iterator().next();
            return String.format("%s is located in %s.", hospitalName, city);
        }

        // Multiple cities - just list the cities, don't give details
        String cities = String.join(", ", hospitalsByCity.keySet());
        return String.format("'%s' exists in multiple cities: %s. Which city do you need?", hospitalName, cities);
    }

    @Tool("Confirm if a specific hospital exists in the network. Use this when user asks questions like 'Is X hospital in my network?' or 'Can you confirm if Y hospital in Z city is covered?'")
    public String confirmHospitalInNetwork(
            @P("The exact or partial name of the hospital to confirm") String hospitalName,
            @P("The city where the hospital is located. IMPORTANT: Ask user for city if not provided") String city) {

        long toolStart = System.currentTimeMillis();

        log.info("========================================");
        log.info("🔎 RAG TOOL: confirmHospitalInNetwork");
        log.info("   Hospital: \"{}\"", hospitalName);
        log.info("   City: \"{}\"", city);
        log.info("========================================");

        // Check if we need clarification about the city
        // Handle null, empty, or placeholder values like "unknown", "not specified",
        // etc.
        boolean needsCityClarity = city == null || city.isEmpty() ||
                city.equalsIgnoreCase("unknown") ||
                city.equalsIgnoreCase("not specified") ||
                city.equalsIgnoreCase("not provided") ||
                city.equalsIgnoreCase("unspecified");

        if (needsCityClarity) {
            long ragStart = System.currentTimeMillis();
            Map<String, List<Hospital>> hospitalsByCity = vectorStoreService
                    .findHospitalsByNameGroupedByCity(hospitalName);
            long ragTime = System.currentTimeMillis() - ragStart;
            log.info("⏱️ LOG TIME: RAG Lookup (by name) = {}ms", ragTime);

            if (hospitalsByCity.isEmpty()) {
                // Hospital doesn't exist at all - ask for city to help narrow down
                return String.format(
                        "I dont have '%s' in my database. Which city are you looking for?",
                        hospitalName);
            }

            if (hospitalsByCity.size() > 1) {
                // Multiple cities - need clarification
                String cities = String.join(", ", hospitalsByCity.keySet());
                return String.format(
                        "I found '%s' in multiple cities: %s. Which city?",
                        hospitalName, cities);
            }

            // Single city - proceed with that
            city = hospitalsByCity.keySet().iterator().next();
        }

        long ragStart = System.currentTimeMillis();
        Optional<Hospital> hospital = vectorStoreService.findExactHospital(hospitalName, city);
        long ragTime = System.currentTimeMillis() - ragStart;
        long totalToolTime = System.currentTimeMillis() - toolStart;

        log.info("⏱️ LOG TIME: RAG Exact Match = {}ms", ragTime);
        log.info("⏱️ LOG TIME: Tool Total = {}ms", totalToolTime);

        if (hospital.isPresent()) {
            Hospital h = hospital.get();

            return String.format(
                    "Yes, %s is in your network. Located in %s. The address is %s.",
                    h.getName(), h.getCity(),
                    h.getAddress() != null ? h.getAddress() : "not available");
        } else {
            // Check if hospital exists in a DIFFERENT city
            Map<String, List<Hospital>> allLocations = vectorStoreService
                    .findHospitalsByNameGroupedByCity(hospitalName);

            if (!allLocations.isEmpty()) {
                // Hospital exists but in different city
                String actualCity = allLocations.keySet().iterator().next();
                Hospital actualHospital = allLocations.get(actualCity).get(0);

                return String.format(
                        "Yes, %s is in your network but in %s, not %s. The address is %s.",
                        actualHospital.getName(),
                        actualCity,
                        city,
                        actualHospital.getAddress() != null ? actualHospital.getAddress() : "not available");
            }

            // Hospital not found
            return String.format("Sorry, '%s' is not in the network database.", hospitalName);
        }
    }

    @Tool("Get a list of hospitals in a specific city. Use when user asks 'What hospitals are in X city?' or 'Show me hospitals near Y'")
    public String getHospitalsByCity(
            @P("The city name to search for hospitals") String city,
            @P("Maximum number of results to return, default 5") int maxResults) {

        long toolStart = System.currentTimeMillis();

        log.info("========================================");
        log.info("🏙️ RAG TOOL: getHospitalsByCity");
        log.info("   City: \"{}\"", city);
        log.info("   Max Results: {}", maxResults);
        log.info("========================================");

        if (city == null || city.isEmpty()) {
            return "I need to know which city you're interested in. Could you please specify the city?";
        }

        if (maxResults <= 0)
            maxResults = 5;

        long ragStart = System.currentTimeMillis();
        List<Hospital> results = vectorStoreService.searchByCity(city, maxResults);
        long ragTime = System.currentTimeMillis() - ragStart;
        long totalToolTime = System.currentTimeMillis() - toolStart;

        log.info("⏱️ LOG TIME: RAG Search (by city) = {}ms", ragTime);
        log.info("⏱️ LOG TIME: Tool Total = {}ms", totalToolTime);

        if (results.isEmpty()) {
            return String.format("No hospitals found in '%s'.", city);
        }

        StringBuilder response = new StringBuilder();
        response.append(String.format("Hospitals in %s: ", city));

        // Compact format: Name - Address
        for (int i = 0; i < results.size(); i++) {
            Hospital h = results.get(i);
            response.append(String.format("%d. %s", i + 1, h.getName()));
            if (h.getAddress() != null && !h.getAddress().isEmpty()) {
                response.append(" - ").append(h.getAddress());
            }
            if (i < results.size() - 1) {
                response.append("; ");
            }
        }

        return response.toString();
    }

    @Tool("Get detailed information about a specific hospital by name. If city is not provided, ASK the user which city.")
    public String getHospitalDetails(
            @P("The name of the hospital to get details for") String hospitalName,
            @P("The city where the hospital is located. If user didnt specify, pass empty string") String city) {

        long toolStart = System.currentTimeMillis();

        log.info("========================================");
        log.info("📋 RAG TOOL: getHospitalDetails");
        log.info("   Hospital: \"{}\"", hospitalName);
        log.info("   City: \"{}\"", city);
        log.info("========================================");

        if (hospitalName == null || hospitalName.isEmpty()) {
            return "I need the hospital name to get details. Which hospital are you interested in?";
        }

        // Check if city is missing or placeholder
        boolean needsCityClarity = city == null || city.isEmpty() ||
                city.equalsIgnoreCase("unknown") ||
                city.equalsIgnoreCase("not specified") ||
                city.equalsIgnoreCase("not provided") ||
                city.equalsIgnoreCase("unspecified") ||
                city.equalsIgnoreCase("null");

        if (needsCityClarity) {
            // First check if this hospital exists in any city
            Map<String, List<Hospital>> hospitalsByCity = vectorStoreService
                    .findHospitalsByNameGroupedByCity(hospitalName);

            if (hospitalsByCity.isEmpty()) {
                // Hospital doesn't exist at all - no point asking for city
                return String.format("'%s' is not in the database.", hospitalName);
            }

            if (hospitalsByCity.size() == 1) {
                // Found in exactly one city - return result directly
                city = hospitalsByCity.keySet().iterator().next();
                Hospital h = hospitalsByCity.get(city).get(0);
                return String.format("Yes, %s is in your network. Located in %s. Address: %s",
                        h.getName(), h.getCity(),
                        h.getAddress() != null ? h.getAddress() : "N/A");
            }

            // Found in multiple cities - ask which one
            String cities = String.join(", ", hospitalsByCity.keySet());
            return String.format("'%s' found in: %s. Which city?", hospitalName, cities);
        }

        long ragStart = System.currentTimeMillis();
        Optional<Hospital> hospital = vectorStoreService.findExactHospital(hospitalName, city);
        long ragTime = System.currentTimeMillis() - ragStart;
        long totalToolTime = System.currentTimeMillis() - toolStart;

        log.info("⏱️ LOG TIME: RAG Lookup = {}ms", ragTime);
        log.info("⏱️ LOG TIME: Tool Total = {}ms", totalToolTime);

        if (hospital.isPresent()) {
            Hospital h = hospital.get();
            return String.format("Yes, %s is in your network. Located in %s. The address is %s.",
                    h.getName(), h.getCity(),
                    h.getAddress() != null ? h.getAddress() : "not available");
        }

        // Hospital not found in specified city - check other cities
        Map<String, List<Hospital>> allLocations = vectorStoreService
                .findHospitalsByNameGroupedByCity(hospitalName);

        if (!allLocations.isEmpty()) {
            String actualCity = allLocations.keySet().iterator().next();
            Hospital h = allLocations.get(actualCity).get(0);
            return String.format("Yes, %s is in your network but in %s, not %s. The address is %s.",
                    h.getName(), actualCity, city,
                    h.getAddress() != null ? h.getAddress() : "not available");
        }

        // Hospital doesnt exist at all
        return String.format("Sorry, '%s' is not in the network database.", hospitalName);
    }

    @Tool("Get emergency contact information and nearest emergency facilities")
    public String getEmergencyInfo(
            @P("Optional: city to find nearest emergency facilities") String city) {

        log.info("TOOL: getEmergencyInfo - City: \"{}\"", city);

        StringBuilder response = new StringBuilder();
        response.append("Emergency: 112 (India), 911 (US), Ambulance: 108. ");

        if (city != null && !city.isEmpty()) {
            List<Hospital> nearbyHospitals = vectorStoreService.searchByCity(city, 3);

            if (!nearbyHospitals.isEmpty()) {
                response.append("Hospitals in ").append(city).append(": ");
                for (int i = 0; i < nearbyHospitals.size(); i++) {
                    Hospital h = nearbyHospitals.get(i);
                    response.append(h.getName());
                    if (i < nearbyHospitals.size() - 1)
                        response.append(", ");
                }
            }
        }

        response.append(" For emergencies, call 112 immediately.");

        return response.toString();
    }

    // Helper methods

    private String buildNoResultsResponse(String query, String city) {
        return city != null && !city.isEmpty()
                ? "No hospitals found in " + city + "."
                : "No hospitals found.";
    }

    private String buildSearchResultsResponse(List<Hospital> results, String query, String city) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Hospital h = results.get(i);
            sb.append(i + 1).append(". ").append(h.getName());
            if (h.getAddress() != null)
                sb.append(" - ").append(h.getAddress());
            if (i < results.size() - 1)
                sb.append(" ");
        }
        return sb.toString();
    }
}
