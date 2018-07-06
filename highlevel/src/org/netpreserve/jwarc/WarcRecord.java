/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2018 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc;

import org.netpreserve.jwarc.parser.ProtocolVersion;
import org.netpreserve.jwarc.parser.WarcHeaderParser;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WarcRecord extends Message {
    private static final Map<String, Constructor> constructors = new ConcurrentHashMap<>();
    private final WarcBody warcBody;

    static {
        constructors.put("continuation", WarcContinuation::new);
        constructors.put("conversion", WarcConversion::new);
        constructors.put("metadata", WarcMetadata::new);
        constructors.put("request", WarcRequest::new);
        constructors.put("resource", WarcResource::new);
        constructors.put("response", WarcResponse::new);
        constructors.put("revisit", WarcRevisit::new);
        constructors.put("warcinfo", Warcinfo::new);
    }

    WarcRecord(ProtocolVersion version, Headers headers, WarcBody body) {
        super(version, headers, body);
        this.warcBody = body;
    }

    static URI parseURI(String uri) {
        if (uri.startsWith("<") && uri.endsWith(">")) {
            uri = uri.substring(1, uri.length() - 1);
        }
        return URI.create(uri);
    }

    @Override
    public WarcBody body() {
        return warcBody;
    }

    /**
     * The type of record.
     */
    public String type() {
        return headers().sole("WARC-Type").get();
    }

    /**
     * The globally unique identifier for this record.
     */
    public URI id() {
        return parseURI(headers().sole("WARC-Record-ID").get());
    }

    /**
     * The instant that data capture for this record began.
     */
    public Instant date() {
        return Instant.parse(headers().sole("WARC-Date").get());
    }


    /**
     * The reason why this record was truncated or {@link TruncationReason#NOT_TRUNCATED}.
     */
    public TruncationReason truncated() {
        return headers().sole("WARC-Truncated")
                .map(value -> TruncationReason.valueOf(value.toUpperCase()))
                .orElse(TruncationReason.NOT_TRUNCATED);
    }

    /**
     * The current record's relative ordering in a sequence of segmented records.
     * <p>
     * In the first segment of any record that is completed in one or more later {@link WarcContinuation} records, this
     * parameter is mandatory. Its value there is "1". In a {@link WarcContinuation} record, this parameter is also
     * mandatory. Its value is the sequence number of the current segment in the logical whole record, increasing by
     * 1 in each next segment.
     */
    public Optional<Long> segmentNumber() {
        return headers().sole("WARC-Segment-Number").map(Long::valueOf);
    }


    public static WarcRecord parse(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
        MapBuildingHandler handler = new MapBuildingHandler();
        WarcHeaderParser parser = new WarcHeaderParser(handler);

        while (true) {
            parser.parse(buffer);
            if (parser.isFinished()) break;
            if (parser.isError()) throw new ParsingException("invalid warc file");
            buffer.compact();
            int n = channel.read(buffer);
            if (n < 0) throw new EOFException();
            buffer.flip();
        }

        Headers headers = new Headers(handler.headerMap);
        WarcBody body = new WarcBody(headers, channel, buffer);
        String type = headers.sole("WARC-Type").orElse("unknown");
        return constructors.getOrDefault(type, WarcRecord::new).construct(handler.version, headers, body);
    }

    public static WarcRecord parse(ReadableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        buffer.flip();
        return parse(channel, buffer);
    }

    public static WarcRecord parse(InputStream stream) throws IOException {
        return parse(Channels.newChannel(stream));
    }

    @FunctionalInterface
    public interface Constructor<R extends WarcRecord> {
        R construct(ProtocolVersion version, Headers headers, WarcBody body);
    }


    protected static String formatId(UUID recordId) {
        return "<urn:uuid:" + recordId + ">";
    }

    protected static String formatId(URI recordId) {
        return "<" + recordId + ">";
    }

    public abstract static class Builder<R extends WarcRecord, B extends Builder<R, B>> extends Message.Builder<R, B> {
        private Map<String, List<String>> headerMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private ProtocolVersion version = ProtocolVersion.WARC_1_0;
        private ReadableByteChannel bodyChannel;

        public Builder(String type) {
            super();
            setHeader("WARC-Type", type);
            setHeader("Content-Length", "0");
            date(Instant.now());
            recordId(UUID.randomUUID());
        }

        public B recordId(UUID uuid) {
            return setHeader("WARC-Record-ID", formatId(uuid));
        }

        public B recordId(URI recordId) {
            return setHeader("WARC-Record-ID", formatId(recordId));
        }

        public B date(Instant date) {
            return setHeader("WARC-Date", date.toString());
        }

        public B blockDigest(String algorithm, String value) {
            return blockDigest(new Digest(algorithm, value));
        }

        public B blockDigest(Digest digest) {
            return addHeader("WARC-Block-Digest", digest.toPrefixedBase32());
        }

        public B truncated(TruncationReason truncationReason) {
            return addHeader("WARC-Truncated", truncationReason.name().toLowerCase());
        }
        public B segmentNumber(long segmentNumber) {
            return addHeader("WARC-Segment-Number", String.valueOf(segmentNumber));
        }

        @Override
        public B addHeader(String name, String value) {
            headerMap.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
            return (B)this;
        }

        @Override
        public B setHeader(String name, String value) {
            List list = new ArrayList();
            list.add(value);
            headerMap.put(name, list);
            return (B)this;
        }

        public B body(String contentType, byte[] contentBytes) {
            setHeader("Content-Type", contentType);
            setHeader("Content-Length", Long.toString(contentBytes.length));
            this.bodyChannel = Channels.newChannel(new ByteArrayInputStream(contentBytes));
            return (B)this;
        }

        protected R build(Constructor<R> constructor) {
            Headers headers = new Headers(headerMap);
            return constructor.construct(version, headers, new WarcBody(headers, bodyChannel, ByteBuffer.allocate(0)));
        }
    }
}