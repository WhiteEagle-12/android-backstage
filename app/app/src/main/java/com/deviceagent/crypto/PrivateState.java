package com.deviceagent.crypto;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
public final class PrivateState {
    private PrivateState() {}
    public static void write(Path file,String text) throws java.io.IOException {
        Files.createDirectories(file.getParent());
        Path temp=Files.createTempFile(file.getParent(),"state-",".tmp");
        Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try(FileChannel ch=FileChannel.open(temp,StandardOpenOption.WRITE)){
            ByteBuffer bytes=ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while(bytes.hasRemaining())ch.write(bytes);ch.force(true);
        }
        Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(FileChannel ch=FileChannel.open(file.getParent(),StandardOpenOption.READ)){ch.force(true);}
    }
}
