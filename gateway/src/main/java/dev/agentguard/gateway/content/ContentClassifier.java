package dev.agentguard.gateway.content;

import java.util.List;

/**
 * Looks at text coming back from a tool and says whether it contains
 * instruction-like content aimed at an AI.
 *
 * This is a risk-reduction layer, not a defence: a clever attacker can phrase
 * an injection that no rule catches. The gateway's permissions do not depend
 * on it. A model-based classifier can be added as another implementation.
 */
public interface ContentClassifier {

    Verdict classify(String text);

    record Verdict(List<String> flags) {
        public boolean suspicious() {
            return !flags.isEmpty();
        }
    }
}
