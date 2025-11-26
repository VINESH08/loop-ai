package com.loopai.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Hospital entity - flexible model that can map to any CSV structure
 * The actual field mapping will be configured based on your CSV columns
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Hospital {

    private String id;
    private String name;
    private String city;
    private String state;
    private String address;
    private String phone;
    private String specialties;
    private String type; // e.g., "Network", "Non-Network"
    private String pincode;

    // Store any additional fields from CSV that don't map to above
    private Map<String, String> additionalFields;

    /**
     * Create a searchable text representation for vector embedding
     */
    public String toSearchableText() {
        StringBuilder sb = new StringBuilder();
        if (name != null)
            sb.append("Hospital: ").append(name).append(". ");
        if (city != null)
            sb.append("City: ").append(city).append(". ");
        if (state != null)
            sb.append("State: ").append(state).append(". ");
        if (address != null)
            sb.append("Address: ").append(address).append(". ");
        if (specialties != null)
            sb.append("Specialties: ").append(specialties).append(". ");
        if (type != null)
            sb.append("Type: ").append(type).append(". ");
        if (pincode != null)
            sb.append("Pincode: ").append(pincode).append(". ");

        // Include additional fields
        if (additionalFields != null) {
            additionalFields.forEach((key, value) -> {
                if (value != null && !value.isEmpty()) {
                    sb.append(key).append(": ").append(value).append(". ");
                }
            });
        }

        return sb.toString();
    }

    /**
     * Create a formatted response string for the user (no emojis for LLM compatibility)
     */
    public String toResponseString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name != null ? name : "Unknown Hospital").append("\n");
        if (city != null)
            sb.append("   City: ").append(city).append("\n");
        if (state != null)
            sb.append("   State: ").append(state).append("\n");
        if (address != null)
            sb.append("   Address: ").append(address).append("\n");
        if (phone != null)
            sb.append("   Phone: ").append(phone).append("\n");
        if (specialties != null)
            sb.append("   Specialties: ").append(specialties).append("\n");
        if (type != null)
            sb.append("   Network Status: ").append(type).append("\n");
        if (pincode != null)
            sb.append("   Pincode: ").append(pincode).append("\n");

        return sb.toString();
    }
}
