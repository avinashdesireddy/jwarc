/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2018 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc;

import org.netpreserve.jwarc.parser.ProtocolVersion;

import java.net.URI;
import java.util.Optional;

public class WarcConversion extends WarcTargetRecord implements HasRefersTo {
    WarcConversion(ProtocolVersion version, Headers headers, WarcBody body) {
        super(version, headers, body);
    }

    @Override
    public Optional<URI> refersTo() {
        return headers().sole("WARC-Refers-To").map(WarcRecords::parseURI);
    }

    public static class Builder extends WarcTargetRecord.Builder<WarcConversion, Builder> {
        public Builder() {
            super("conversion");
        }

        public Builder refersTo(URI recordId) {
            return addHeader("WARC-Refers-To", WarcRecords.formatId(recordId));
        }

        @Override
        public WarcConversion build() {
            return build(WarcConversion::new);
        }
    }
}
