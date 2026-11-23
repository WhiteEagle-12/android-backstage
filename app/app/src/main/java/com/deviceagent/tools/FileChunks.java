package com.deviceagent.tools;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Bounded file reads with a version check before and after each chunk. */
public final class FileChunks {
    public static final int MAX_BYTES=1024*1024;
    private FileChunks(){}
    public static Map<String,Object> read(String name,Long offset,Integer length,String expected){
        long start=offset==null?0:offset;
        if(start<0||length!=null&&(length<1||length>MAX_BYTES))throw new IllegalArgumentException("Use a nonnegative offset and a length from 1 to 1048576 bytes");
        if(expected!=null&&!expected.matches("file_[a-f0-9]{64}"))throw new IllegalArgumentException("Use fileId from the first chunk");
        try{
            ActionDeadline.check();Path path=Paths.get(name).toRealPath();
            var before=Files.readAttributes(path,BasicFileAttributes.class);
            if(!before.isRegularFile())throw fail("not_a_file","Choose a regular file to download.");
            String version=identity(path,before);
            if(expected!=null&&!expected.equals(version))throw changed();
            if(offset==null&&length==null&&before.size()>MAX_BYTES)throw fail("file_too_large","Read this file in chunks: start with offset:0,length:1048576, then use nextOffset and fileId from each result.");
            if(start>before.size())throw fail("invalid_file_offset","The offset is past the end of the file. Start at offset 0.");
            int count=(int)Math.min(length==null?MAX_BYTES:length,before.size()-start);
            byte[] bytes=new byte[count];
            try(var channel=FileChannel.open(path,StandardOpenOption.READ)){
                channel.position(start);var buffer=ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining()){ActionDeadline.check();if(channel.read(buffer)<0)throw changed();}
                if(channel.size()!=before.size())throw changed();
            }
            if(!version.equals(identity(path,Files.readAttributes(path,BasicFileAttributes.class))))throw changed();
            long next=start+count;
            return Json.mapOf("path",name,"bytes",count,"totalBytes",before.size(),"offset",start,"nextOffset",next,"eof",next==before.size(),"fileId",version,"sha256",hash(bytes),"dataBase64",Base64.getEncoder().encodeToString(bytes));
        }catch(InputNotSentException e){throw e;}
        catch(InvalidPathException e){throw new IllegalArgumentException("Use a valid file path");}
        catch(Exception e){throw fail("file_unavailable","The file could not be read. Check its path and Android access permissions.");}
    }
    private static String identity(Path path,BasicFileAttributes attrs)throws Exception{
        Object changedAt;
        try{changedAt=Files.getAttribute(path,"unix:ctime");}catch(UnsupportedOperationException|IllegalArgumentException e){changedAt=attrs.lastModifiedTime();}
        return "file_"+hash((path+"\n"+attrs.fileKey()+"\n"+attrs.size()+"\n"+attrs.lastModifiedTime()+"\n"+changedAt).getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(byte[] value)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value))out.append(String.format(Locale.ROOT,"%02x",b));return out.toString();}
    private static InputNotSentException fail(String code,String message){return new InputNotSentException(code,message);}
    private static InputNotSentException changed(){return fail("file_changed","The file changed during download. Start again from offset 0 to avoid combining different versions.");}
}
