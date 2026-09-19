package top.vrilhyc.applications.player.browser;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded fMP4 remuxer. Reads box metadata and audio sample ranges, never video sample payloads. */
final class Mp4Audio {
    static final int MAX_METADATA = 256 * 1024, MAX_AUDIO = 2 * 1024 * 1024;
    interface Ranges {
        long length();
        byte[] read(long offset, int length) throws Exception;
    }
    record Init(byte[] bytes, int track, int defaultSize) {}
    private record Box(int offset, int size, String type) {
        int payload() { return offset + 8; }
        int end() { return offset + size; }
        byte[] copy(byte[] bytes) { return Arrays.copyOfRange(bytes, offset, end()); }
    }
    private record Part(long offset, int length, byte[] trun) {}
    private record Fragment(long offset, byte[] bytes) {}
    private record Region(long start, long end) {}

    static Init initialization(byte[] source) throws IOException {
        if (source.length > MAX_METADATA) throw invalid();
        List<Box> top = boxes(source, 0, source.length);
        Box moov = required(top, "moov");
        List<Box> children = boxes(source, moov.payload(), moov.end());
        Box audio = null; int track = 0;
        for (Box trak : children) if (trak.type.equals("trak")) {
            List<Box> parts = boxes(source, trak.payload(), trak.end());
            Box mdia = required(parts, "mdia");
            List<Box> media = boxes(source, mdia.payload(), mdia.end());
            Box hdlr = required(media, "hdlr");
            if (hdlr.size < 24 || !type(source, hdlr.payload() + 8).equals("soun")) continue;
            if (audio != null) throw invalid();
            Box tkhd = required(parts, "tkhd");
            int pos = tkhd.payload() + (source[tkhd.payload()] == 1 ? 20 : 12);
            if (pos + 4 > tkhd.end()) throw invalid();
            track = integer(source, pos); audio = trak;
            Box minf = required(media, "minf");
            Box stbl = required(boxes(source, minf.payload(), minf.end()), "stbl");
            Box stsd = required(boxes(source, stbl.payload(), stbl.end()), "stsd");
            if (stsd.size < 24 || integer(source, stsd.payload() + 4) != 1
                    || !type(source, stsd.payload() + 12).equals("mp4a")) throw invalid();
        }
        if (audio == null || track <= 0) throw invalid();
        Box mvex = required(children, "mvex");
        byte[] trex = null; int defaultSize = 0;
        for (Box box : boxes(source, mvex.payload(), mvex.end())) if (box.type.equals("trex")
                && box.size >= 32 && integer(source, box.payload() + 4) == track) {
            trex = box.copy(source); defaultSize = integer(source, box.payload() + 16);
        }
        if (trex == null) throw invalid();
        var movie = new ByteArrayOutputStream();
        movie.write(required(children, "mvhd").copy(source)); movie.write(audio.copy(source)); movie.write(box("mvex", trex));
        return new Init(join(required(top, "ftyp").copy(source), box("moov", movie.toByteArray())), track, defaultSize);
    }

    static byte[] segment(Ranges ranges, Init init) throws Exception {
        if (ranges.length() < 8 || ranges.length() > 64L * 1024 * 1024) throw invalid();
        var fragments = new ArrayList<Fragment>(); var media = new ArrayList<Region>();
        long position = 0; int count = 0;
        while (position < ranges.length()) {
            if (++count > 128 || position + 8 > ranges.length()) throw invalid();
            byte[] header = ranges.read(position, 8);
            long size = Integer.toUnsignedLong(integer(header, 0)); String kind = type(header, 4);
            if (size < 8 || position + size > ranges.length()) throw invalid();
            if (kind.equals("moof")) {
                if (size > MAX_METADATA) throw invalid();
                fragments.add(new Fragment(position, join(header, ranges.read(position + 8, (int)size - 8))));
            } else if (kind.equals("mdat")) media.add(new Region(position + 8, position + size));
            position += size;
        }
        var result = new ByteArrayOutputStream();
        int audioBytes = 0;
        for (Fragment fragment : fragments) {
            byte[] bytes = fragment.bytes;
            var children = boxes(bytes, 8, bytes.length);
            Box audio = null;
            for (Box traf : children) if (traf.type.equals("traf")) {
                Box tfhd = required(boxes(bytes, traf.payload(), traf.end()), "tfhd");
                if (tfhd.size < 16) throw invalid();
                if (integer(bytes, tfhd.payload() + 4) == init.track) {
                    if (audio != null) throw invalid();
                    audio = traf;
                }
            }
            if (audio == null) continue;
            var parts = boxes(bytes, audio.payload(), audio.end());
            if (parts.stream().anyMatch(b -> !Set.of("tfhd", "tfdt", "trun").contains(b.type))) throw invalid();
            Box tfhd = required(parts, "tfhd");
            byte[] header = tfhd.copy(bytes);
            int flags = integer(header, 8) & 0xffffff, cursor = 16;
            long base;
            if ((flags & 1) != 0) { base = longInteger(header, cursor); cursor += 8; }
            else if ((flags & 0x020000) != 0) base = fragment.offset;
            else throw invalid();
            if ((flags & 2) != 0) cursor += 4;
            if ((flags & 8) != 0) cursor += 4;
            int defaultSize = (flags & 16) != 0 ? integer(header, cursor) : init.defaultSize;
            // Normalize absolute base offsets to the start of the newly constructed moof.
            if ((flags & 1) != 0) header = join(Arrays.copyOfRange(header, 0, 16), Arrays.copyOfRange(header, 24, header.length));
            put(header, 0, header.length); put(header, 8, (integer(header, 8) & ~1) | 0x020000);
            var runs = new ArrayList<Part>(); long previousEnd = -1;
            for (Box trun : parts) if (trun.type.equals("trun")) {
                byte[] run = trun.copy(bytes);
                int runFlags = integer(run, 8) & 0xffffff;
                int samples = integer(run, 12), offset = 16;
                if (samples <= 0 || samples > 4096) throw invalid();
                long dataOffset;
                if ((runFlags & 1) != 0) { dataOffset = base + integer(run, offset); offset += 4; }
                else { if (previousEnd < 0) throw invalid(); dataOffset = previousEnd; }
                if ((runFlags & 4) != 0) offset += 4;
                long length = 0;
                for (int i = 0; i < samples; i++) {
                    if ((runFlags & 0x100) != 0) offset += 4;
                    int size = (runFlags & 0x200) != 0 ? integer(run, offset) : defaultSize;
                    if ((runFlags & 0x200) != 0) offset += 4;
                    if ((runFlags & 0x400) != 0) offset += 4;
                    if ((runFlags & 0x800) != 0) offset += 4;
                    if (size <= 0 || (length += size) > MAX_AUDIO || offset > run.length) throw invalid();
                }
                if (offset != run.length) throw invalid();
                final long end = dataOffset + length;
                if (dataOffset < 0 || media.stream().noneMatch(r -> dataOffset >= r.start && end <= r.end)) throw invalid();
                previousEnd = end;
                if ((runFlags & 1) == 0) {
                    run = join(Arrays.copyOfRange(run, 0, 16), new byte[4], Arrays.copyOfRange(run, 16, run.length));
                    put(run, 0, run.length); put(run, 8, integer(run, 8) | 1);
                }
                runs.add(new Part(dataOffset, (int)length, run));
            }
            if (runs.isEmpty()) throw invalid();
            byte[] mfhd = required(children, "mfhd").copy(bytes), tfdt = required(parts, "tfdt").copy(bytes);
            int moofSize = 8 + mfhd.length + 8 + header.length + tfdt.length + runs.stream().mapToInt(p -> p.trun.length).sum();
            var samples = new ByteArrayOutputStream(); var traf = new ByteArrayOutputStream();
            traf.write(header); traf.write(tfdt);
            for (Part run : runs) {
                audioBytes += run.length; if (audioBytes > MAX_AUDIO) throw invalid();
                put(run.trun, 16, moofSize + 8 + samples.size()); traf.write(run.trun);
                samples.write(ranges.read(run.offset, run.length));
            }
            result.write(box("moof", join(mfhd, box("traf", traf.toByteArray()))));
            result.write(box("mdat", samples.toByteArray()));
        }
        if (result.size() == 0) throw invalid();
        return result.toByteArray();
    }
    private static List<Box> boxes(byte[] bytes, int from, int to) throws IOException {
        var result = new ArrayList<Box>();
        for (int pos = from; pos < to;) {
            if (pos + 8 > to || result.size() >= 1024) throw invalid();
            int size = integer(bytes, pos);
            if (size < 8 || size > to - pos) throw invalid();
            result.add(new Box(pos, size, type(bytes, pos + 4))); pos += size;
        }
        return result;
    }
    private static Box required(List<Box> boxes, String type) throws IOException {
        return boxes.stream().filter(b -> b.type.equals(type)).findFirst().orElseThrow(Mp4Audio::invalid);
    }
    private static int integer(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 4 > bytes.length) throw invalid();
        return ByteBuffer.wrap(bytes, offset, 4).getInt();
    }
    private static long longInteger(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 8 > bytes.length) throw invalid();
        return ByteBuffer.wrap(bytes, offset, 8).getLong();
    }
    private static String type(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 4 > bytes.length) throw invalid();
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }
    private static void put(byte[] bytes, int offset, int value) { ByteBuffer.wrap(bytes, offset, 4).putInt(value); }
    private static byte[] box(String type, byte[] payload) throws IOException {
        var output = new ByteArrayOutputStream(); var data = new DataOutputStream(output);
        data.writeInt(8 + payload.length); data.writeBytes(type); data.write(payload); return output.toByteArray();
    }
    private static byte[] join(byte[]... items) throws IOException {
        var output = new ByteArrayOutputStream(); for (byte[] item : items) output.write(item); return output.toByteArray();
    }
    private static IOException invalid() { return new IOException("该音轨封装暂不支持纯音频读取，请恢复画面或使用独立音频 HLS"); }
}
