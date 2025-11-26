package com.loopai.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for mapping CSV columns to Hospital fields
 * Configure these in application.properties to match YOUR CSV structure
 * 
 * Your CSV columns: "HOSPITAL NAME ", "Address", "CITY"
 */
@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "csv.mapping")
@Data
public class CsvColumnMapping {

    // Map these to your actual CSV column names in application.properties
    private String nameColumn = "HOSPITAL NAME";
    private String cityColumn = "CITY";
    private String addressColumn = "Address";

    // Path to your CSV file
    private String filePath = "classpath:data/hospitals.csv";
}
