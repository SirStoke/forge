package forge.ai.llm;

/** Native harness model IDs. Empty values keep the harness defaults. */
public record AcpModels(String fastModel, String planningModel) {
    public AcpModels {
        fastModel = fastModel == null ? "" : fastModel.trim();
        planningModel = planningModel == null ? "" : planningModel.trim();
    }

    public static AcpModels defaults() { return new AcpModels("", ""); }

    record OpenCodeModel(String model, String effort) { }

    static OpenCodeModel parseOpenCode(String value) {
        final int fragment = value.lastIndexOf('#');
        return fragment < 0 ? new OpenCodeModel(value, "")
                : new OpenCodeModel(value.substring(0, fragment), value.substring(fragment + 1));
    }

    AcpModels resolve(String agentId) {
        if (!AcpAgentRegistry.CODEX.equals(agentId)) return this;
        return new AcpModels(fastModel.isEmpty() ? System.getProperty("forge.llm.model", AcpAgent.MODEL) : fastModel,
                planningModel.isEmpty() ? System.getProperty("forge.llm.planningModel", "gpt-6.1-sol") : planningModel);
    }

    public String validationError(String agentId) {
        for (String model : new String[] {fastModel, planningModel}) {
            if (model.isEmpty()) continue;
            if (model.length() > 256 || model.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
                return "Model IDs must be at most 256 characters and contain no whitespace or control characters.";
            }
            if ("opencode".equals(agentId) && !model.matches("[^/#]+/[^#]+(?:#[^/#]+)?")) {
                return "OpenCode models must use provider/model or provider/model#effort, for example openai/gpt-6.1-sol#high.";
            }
        }
        return "";
    }
}
