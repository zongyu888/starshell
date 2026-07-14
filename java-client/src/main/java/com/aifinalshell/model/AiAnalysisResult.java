package com.aifinalshell.model;

public class AiAnalysisResult {
    private String summary;
    private String severity;
    private String rootCause;
    private String fixCommand;
    private String explanation;

    public AiAnalysisResult() {}

    public AiAnalysisResult(String summary, String severity, String rootCause, String fixCommand, String explanation) {
        this.summary = summary;
        this.severity = severity;
        this.rootCause = rootCause;
        this.fixCommand = fixCommand;
        this.explanation = explanation;
    }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }

    public String getFixCommand() { return fixCommand; }
    public void setFixCommand(String fixCommand) { this.fixCommand = fixCommand; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
}
