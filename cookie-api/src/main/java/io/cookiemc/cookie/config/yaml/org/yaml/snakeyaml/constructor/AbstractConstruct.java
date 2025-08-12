//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.constructor;

import io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.error.YAMLException;
import io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes.Node;

public abstract class AbstractConstruct implements Construct {
    public AbstractConstruct() {
    }

    public void construct2ndStep(Node node, Object data) {
        if (node.isTwoStepsConstruction()) {
            throw new IllegalStateException("Not Implemented in " + this.getClass().getName());
        } else {
            throw new YAMLException("Unexpected recursive structure for Node: " + node);
        }
    }
}
