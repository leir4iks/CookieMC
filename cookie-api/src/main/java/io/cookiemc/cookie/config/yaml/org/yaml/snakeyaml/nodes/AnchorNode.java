//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes;

public class AnchorNode extends io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes.Node {
    private final io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes.Node realNode;

    public AnchorNode(io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes.Node realNode) {
        super(realNode.getTag(), realNode.getStartMark(), realNode.getEndMark());
        this.realNode = realNode;
    }

    public io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.nodes.NodeId getNodeId() {
        return NodeId.anchor;
    }

    public Node getRealNode() {
        return this.realNode;
    }
}
