//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes;

public final class NodeTuple {
    private final io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes.Node keyNode;
    private final io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes.Node valueNode;

    public NodeTuple(io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes.Node keyNode, io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes.Node valueNode) {
        if (keyNode != null && valueNode != null) {
            this.keyNode = keyNode;
            this.valueNode = valueNode;
        } else {
            throw new NullPointerException("Nodes must be provided.");
        }
    }

    public io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes.Node getKeyNode() {
        return this.keyNode;
    }

    public Node getValueNode() {
        return this.valueNode;
    }

    public String toString() {
        return "<NodeTuple keyNode=" + this.keyNode + "; valueNode=" + this.valueNode + ">";
    }
}
