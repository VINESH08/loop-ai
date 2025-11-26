package com.loopai.backend.service;

import com.loopai.backend.config.CsvColumnMapping;
import com.loopai.backend.model.Hospital;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Service to load hospital data from CSV file
 * Flexible mapping - configure column names in application.properties
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CsvLoaderService {

    private final ResourceLoader resourceLoader;
    private final CsvColumnMapping columnMapping;

    private List<Hospital> hospitals = new ArrayList<>();
    private Map<String, Integer> columnIndexMap = new HashMap<>();

    @PostConstruct
    public void init() {
        loadCsvData();
    }

    public void loadCsvData() {
        try {
            Resource resource = resourceLoader.getResource(columnMapping.getFilePath());

            if (!resource.exists()) {
                log.warn("CSV file not found at: {}. Please place your CSV file there.",
                        columnMapping.getFilePath());
                return;
            }

            try (CSVReader reader = new CSVReaderBuilder(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))
                    .build()) {

                List<String[]> allRows = reader.readAll();

                if (allRows.isEmpty()) {
                    log.warn("CSV file is empty");
                    return;
                }

                // First row is header - build column index map
                String[] headers = allRows.get(0);
                buildColumnIndexMap(headers);

                log.info("========================================");
                log.info("📊 CSV LOADER - Loading hospital data");
                log.info("========================================");
                log.info("📁 File: {}", columnMapping.getFilePath());
                log.info("📋 Columns found: {}", Arrays.toString(headers));
                log.info("📊 Total rows (excluding header): {}", allRows.size() - 1);

                // Process data rows
                hospitals.clear();
                for (int i = 1; i < allRows.size(); i++) {
                    String[] row = allRows.get(i);
                    Hospital hospital = mapRowToHospital(row, headers, i);
                    if (hospital != null) {
                        hospitals.add(hospital);
                    }
                }

                log.info("✅ Successfully loaded {} hospitals", hospitals.size());
                log.info("========================================");

            }
        } catch (IOException | CsvException e) {
            log.error("Failed to load CSV file: {}", e.getMessage(), e);
        }
    }

    private void buildColumnIndexMap(String[] headers) {
        columnIndexMap.clear();
        for (int i = 0; i < headers.length; i++) {
            // Normalize header names (trim, lowercase)
            String normalizedHeader = headers[i].trim().toLowerCase();
            columnIndexMap.put(normalizedHeader, i);
            // Also store original for exact match
            columnIndexMap.put(headers[i].trim(), i);
        }
    }

    private Hospital mapRowToHospital(String[] row, String[] headers, int rowNum) {
        try {
            Hospital.HospitalBuilder builder = Hospital.builder();
            builder.id(String.valueOf(rowNum));

            // Map configured columns
            builder.name(getColumnValue(row, columnMapping.getNameColumn()));
            builder.city(getColumnValue(row, columnMapping.getCityColumn()));

            builder.address(getColumnValue(row, columnMapping.getAddressColumn()));

            // Store any additional fields not mapped above
            Map<String, String> additionalFields = new HashMap<>();

            // Build set of mapped columns, filtering out nulls and empty strings
            Set<String> mappedColumns = new HashSet<>();
            addIfNotEmpty(mappedColumns, columnMapping.getNameColumn());
            addIfNotEmpty(mappedColumns, columnMapping.getCityColumn());

            addIfNotEmpty(mappedColumns, columnMapping.getAddressColumn());

            for (int i = 0; i < headers.length && i < row.length; i++) {
                String header = headers[i].trim().toLowerCase();
                if (!mappedColumns.contains(header)) {
                    String value = row[i] != null ? row[i].trim() : "";
                    if (!value.isEmpty()) {
                        additionalFields.put(headers[i].trim(), value);
                    }
                }
            }
            builder.additionalFields(additionalFields);

            return builder.build();

        } catch (Exception e) {
            log.warn("Failed to parse row {}: {}", rowNum, e.getMessage());
            return null;
        }
    }

    private String getColumnValue(String[] row, String columnName) {
        if (columnName == null || columnName.isEmpty()) {
            return null;
        }

        // Try exact match first, then case-insensitive
        Integer index = columnIndexMap.get(columnName);
        if (index == null) {
            index = columnIndexMap.get(columnName.toLowerCase());
        }

        if (index != null && index < row.length) {
            String value = row[index];
            return (value != null && !value.trim().isEmpty()) ? value.trim() : null;
        }

        return null;
    }

    public List<Hospital> getAllHospitals() {
        return new ArrayList<>(hospitals);
    }

    public int getHospitalCount() {
        return hospitals.size();
    }

    /**
     * Reload CSV data (useful if file is updated)
     */
    public void reload() {
        log.info("Reloading CSV data...");
        loadCsvData();
    }

    /**
     * Helper to add non-empty column names to the set
     */
    private void addIfNotEmpty(Set<String> set, String value) {
        if (value != null && !value.trim().isEmpty()) {
            set.add(value.toLowerCase().trim());
        }
    }
}
