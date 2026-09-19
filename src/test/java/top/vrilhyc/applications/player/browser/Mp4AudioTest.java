package top.vrilhyc.applications.player.browser;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Mp4AudioTest {
    static byte[] ints(int... values) { var b = ByteBuffer.allocate(values.length * 4); for (int v : values) b.putInt(v); return b.array(); }
    static byte[] join(byte[]... bytes) { var b = new ByteArrayOutputStream(); for (byte[] v : bytes) b.writeBytes(v); return b.toByteArray(); }
    static byte[] box(String type, byte[]... bytes) { byte[] data = join(bytes); return join(ints(data.length + 8), type.getBytes(StandardCharsets.US_ASCII), data); }
    static byte[] track(int id, String handler, String codec) {
        return box("trak", box("tkhd", ints(3,0,0,id,0)), box("mdia", box("hdlr", ints(0,0),handler.getBytes(StandardCharsets.US_ASCII),ints(0)),
                box("minf", box("stbl", box("stsd", ints(0,1), box(codec, ints(0,0)))))));
    }
    static byte[] init() {
        return boxAndMovie(track(1,"vide","avc1"), track(7,"soun","mp4a"));
    }
    static byte[] boxAndMovie(byte[]... tracks) {
        return join(box("ftyp", "iso6".getBytes(StandardCharsets.US_ASCII)),
                box("moov", box("mvhd", ints(0,0,0,1000)),join(tracks),
                        box("mvex", box("trex",ints(0,1,1,0,0,0)),box("trex",ints(0,7,1,1024,3,0)))));
    }
    static byte[] fragment(boolean absoluteBase, long base, int gap) {
        byte[] tfhd = box("tfhd", absoluteBase ? join(ints(0x19,7),ByteBuffer.allocate(8).putLong(base).array(),ints(1024,3)) : ints(0x020018,7,1024,3));
        byte[] trun = box("trun",ints(1,2,0));
        byte[] traf = box("traf",tfhd,box("tfdt",ints(0,4096)),trun);
        byte[] moof = box("moof",box("mfhd",ints(0,42)),traf);
        ByteBuffer.wrap(moof).putInt(moof.length-4,moof.length+8+gap);
        byte[] video = new byte[gap]; Arrays.fill(video,(byte)0x55);
        return join(moof,box("mdat",video,new byte[]{1,2,3,4,5,6}));
    }
    @Test void stripsVideoTrackAndReadsOnlyAudioSamplesAcrossMultipleFragments() throws Exception {
        var init = Mp4Audio.initialization(init());
        assertEquals(7,init.track()); assertFalse(new String(init.bytes(),StandardCharsets.ISO_8859_1).contains("vide"));
        byte[] first = fragment(false,0,4000), second = fragment(true,first.length,6000);
        byte[] original = join(first,second);
        int firstVideoStart = ByteBuffer.wrap(first).getInt() + 8;
        int secondVideoStart = first.length + ByteBuffer.wrap(second).getInt() + 8;
        int[] downloaded = {0};
        byte[] filtered = Mp4Audio.segment(new Mp4Audio.Ranges() {
            public long length() { return original.length; }
            public byte[] read(long offset,int length) {
                assertFalse(offset < firstVideoStart + 4000 && offset + length > firstVideoStart, "Read video payload");
                assertFalse(offset < secondVideoStart + 6000 && offset + length > secondVideoStart, "Read video payload");
                downloaded[0] += length; return Arrays.copyOfRange(original,(int)offset,(int)offset+length);
            }
        },init);
        assertTrue(downloaded[0] < original.length / 10);
        for (int pos = 0; pos < filtered.length;) {
            int moofSize = ByteBuffer.wrap(filtered,pos,4).getInt();
            int mdatSize = ByteBuffer.wrap(filtered,pos+moofSize,4).getInt();
            assertEquals(14,mdatSize);
            assertArrayEquals(new byte[]{1,2,3,4,5,6},Arrays.copyOfRange(filtered,pos+moofSize+8,pos+moofSize+mdatSize));
            int trunType = indexOf(filtered,"trun",pos);
            assertEquals(moofSize+8,ByteBuffer.wrap(filtered,trunType+12,4).getInt());
            pos += moofSize + mdatSize;
        }
    }
    static int indexOf(byte[] bytes,String text,int from) { return new String(bytes,StandardCharsets.ISO_8859_1).indexOf(text,from); }
    @Test void rejectsEncryptionMissingAudioAndOutOfBoundsSamples() throws Exception {
        assertThrows(IOException.class,()->Mp4Audio.initialization(boxAndMovie(track(7,"soun","enca"))));
        assertThrows(IOException.class,()->Mp4Audio.initialization(boxAndMovie(track(1,"vide","avc1"))));
        byte[] fragment = fragment(false,0,10);
        int trun = indexOf(fragment,"trun",0);
        ByteBuffer.wrap(fragment).putInt(trun+12,Integer.MAX_VALUE);
        assertThrows(IOException.class,()->Mp4Audio.segment(new Mp4Audio.Ranges() {
            public long length() { return fragment.length; }
            public byte[] read(long offset,int count) { return Arrays.copyOfRange(fragment,(int)offset,(int)offset+count); }
        },Mp4Audio.initialization(init())));
        assertThrows(IOException.class,()->Mp4Audio.initialization(new byte[]{0,0,0,4,109,111,111,118}));
    }
}
