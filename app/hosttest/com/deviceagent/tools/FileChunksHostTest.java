package com.deviceagent.tools;

import java.nio.file.*;
import java.util.*;

public final class FileChunksHostTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejected(String code,Runnable call){try{call.run();throw new AssertionError("Expected "+code);}catch(InputNotSentException e){check(code.equals(e.code),"expected "+code+", got "+e.code);}}
    public static void run()throws Exception{
        Path dir=Files.createTempDirectory("backstage-file-check-"),file=dir.resolve("large.bin");
        try{
            byte[] content=new byte[FileChunks.MAX_BYTES*2+123];new Random(718).nextBytes(content);Files.write(file,content);
            rejected("file_too_large",()->FileChunks.read(file.toString(),null,null,null));
            var output=new java.io.ByteArrayOutputStream();String id=null;long offset=0;
            for(int step=0;step<3;step++){
                var row=FileChunks.read(file.toString(),offset,FileChunks.MAX_BYTES,id);
                var bytes=Base64.getDecoder().decode((String)row.get("dataBase64"));output.write(bytes);
                check(bytes.length<=FileChunks.MAX_BYTES,"bounded read");check(((Number)row.get("totalBytes")).longValue()==content.length,"total size");
                if(id==null)id=(String)row.get("fileId");else check(id.equals(row.get("fileId")),"stable version");
                check(((Number)row.get("offset")).longValue()==offset,"requested offset");offset=((Number)row.get("nextOffset")).longValue();
                check(Boolean.TRUE.equals(row.get("eof"))==(step==2),"end only after final chunk");
                var expected=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);var actual=java.util.HexFormat.of().formatHex(expected);check(actual.equals(row.get("sha256")),"chunk integrity");
            }
            check(Arrays.equals(content,output.toByteArray()),"exact large file reconstruction");
            var end=FileChunks.read(file.toString(),offset,100,id);check(((Number)end.get("bytes")).intValue()==0&&Boolean.TRUE.equals(end.get("eof")),"empty EOF chunk");
            rejected("invalid_file_offset",()->FileChunks.read(file.toString(),(long)content.length+1,100,null));
            String old=id;Files.write(file,new byte[32]);rejected("file_changed",()->FileChunks.read(file.toString(),0L,20,old));
            var first=FileChunks.read(file.toString(),null,null,null);check(((Number)first.get("bytes")).intValue()==32,"small file compatible");
            Files.delete(file);Files.write(file,new byte[32]);String previous=(String)first.get("fileId");rejected("file_changed",()->FileChunks.read(file.toString(),0L,20,previous));
            rejected("not_a_file",()->FileChunks.read(dir.toString(),null,null,null));
            rejected("file_unavailable",()->FileChunks.read(dir.resolve("missing").toString(),null,null,null));
            try(var deadline=ActionDeadline.until(System.currentTimeMillis()-1)){rejected("deadline_exceeded",()->FileChunks.read(file.toString(),0L,10,null));}
            Files.write(file,new byte[0]);check(Boolean.TRUE.equals(FileChunks.read(file.toString(),null,null,null).get("eof")),"empty file");
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(dir);}
        System.out.println("PASS file chunks: exact transfer, bounds, versions, integrity, empty files and deadlines");
    }
}
