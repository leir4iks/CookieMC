//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.tokens;

import io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.error.Mark;

public final class TagToken extends io.cookiemc.cookie.config.yaml.org.yaml.snakeyaml.tokens.Token {
    private final TagTuple value;

    public TagToken(TagTuple value, Mark startMark, Mark endMark) {
        super(startMark, endMark);
        this.value = value;
    }

    public TagTuple getValue() {
        return this.value;
    }

    public ID getTokenId() {
        return ID.Tag;
    }
}
