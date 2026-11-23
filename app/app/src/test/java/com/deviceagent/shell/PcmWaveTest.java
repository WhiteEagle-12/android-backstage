package com.deviceagent.shell;
import com.deviceagent.tools.PcmWave;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PcmWaveTest {
 @Test public void readsFinalizedWaveAndRejectsTruncation() throws Exception {
  Path p=Files.createTempFile("wave-test", ".wav");
  try(var f=FileChannel.open(p,StandardOpenOption.READ,StandardOpenOption.WRITE)){
   PcmWave.header(f,48000,2,8);f.write(ByteBuffer.wrap(new byte[8]),44);
   var fmt=PcmWave.parse(f);assertEquals(48000,fmt.rate());assertEquals(2,fmt.channels());assertEquals(44,fmt.offset());assertEquals(8,fmt.bytes());
   f.truncate(49);assertThrows(java.io.IOException.class,()->PcmWave.parse(f));
  }finally{Files.delete(p);}
 }
 @Test public void rejectsCompressedAndImpossibleFrameSizes() throws Exception {
  Path p=Files.createTempFile("wave-test", ".wav");
  try(var f=FileChannel.open(p,StandardOpenOption.READ,StandardOpenOption.WRITE)){
   PcmWave.header(f,48000,1,4);f.write(ByteBuffer.wrap(new byte[4]),44);
   f.write(ByteBuffer.wrap(new byte[]{3,0}),20);assertThrows(java.io.IOException.class,()->PcmWave.parse(f));
   PcmWave.header(f,48000,2,3);assertThrows(java.io.IOException.class,()->PcmWave.parse(f));
   assertThrows(java.io.IOException.class,()->PcmWave.header(f,48000,2,0xffffffffL));
  }finally{Files.delete(p);}
 }
 @Test public void skipsUnknownOddSizedChunk() throws Exception {
  Path p=Files.createTempFile("wave-test", ".wav");
  try(var f=FileChannel.open(p,StandardOpenOption.READ,StandardOpenOption.WRITE)){
   PcmWave.header(f,48000,1,0);
   ByteBuffer extra=ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
   extra.put(new byte[]{'J','U','N','K'}).putInt(1).put((byte)9).put((byte)0).put(new byte[]{'d','a','t','a'}).putInt(0).flip();f.write(extra,36);
   ByteBuffer size=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(46);size.flip();f.write(size,4);
   assertEquals(54,PcmWave.parse(f).offset());
  }finally{Files.delete(p);}
 }
}
