package com.aifinalshell.agent;

import java.util.List;

/**
 * AI Agent definition
 */
public class AgentDef {
    private String id;
    private String name;
    private String description;
    private String systemPrompt;
    private List<String> capabilities;
    private String model; // "auto" means use active model

    public AgentDef() {}

    public AgentDef(String id, String name, String description, String systemPrompt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.model = "auto";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
