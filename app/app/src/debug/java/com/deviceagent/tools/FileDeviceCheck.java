package com.deviceagent.tools;

import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.nio.file.*;
import java.util.*;

/** Large-file acceptance through the public engine without opening any display. */
public final class FileDeviceCheck {
    public static void main(String[] args){
        DeviceCheckGuard.start("FileDeviceCheck");
        Path file=Paths.get("/data/local/tmp/backstage-file-check-"+UUID.randomUUID()+".bin");int exit=1;
        try{
            if(android.os.Looper.myLooper()==null)android.os.Looper.prepareMainLooper();var context=SystemContext.create("com.androidbackstage.app");
            var tools=new AndroidTools(new LocalExecutor(new VirtualDisplayHost(context),new AccessibilityHost(),new InteractionHost(context)),"com.androidbackstage.app","/data/local/tmp/backstage-files-check/config.json");
            byte[] bytes=new byte[FileChunks.MAX_BYTES*2+197];new Random(207).nextBytes(bytes);Files.write(file,bytes);
            var whole=tools.call("android.pull",Map.of("path",file.toString()));ParityDeviceCheck.check("file_too_large".equals(whole.get("code")),"large file gives chunk instructions without allocating the full result");
            String fileId=null;long offset=0;var received=new java.io.ByteArrayOutputStream();
            while(offset<bytes.length){
                var input=new LinkedHashMap<String,Object>();input.put("path",file.toString());input.put("offset",offset);input.put("length",FileChunks.MAX_BYTES);if(fileId!=null)input.put("fileId",fileId);
                var chunk=tools.call("android.pull",input);if(!"succeeded".equals(chunk.get("outcome")))throw new AssertionError(Json.stringify(chunk));
                fileId=(String)chunk.get("fileId");var part=Base64.getDecoder().decode((String)chunk.get("dataBase64"));received.write(part);offset=((Number)chunk.get("nextOffset")).longValue();
                ParityDeviceCheck.check(part.length<=FileChunks.MAX_BYTES&&Boolean.TRUE.equals(chunk.get("eof"))==(offset==bytes.length),"bounded chunk and exact progress");
            }
            ParityDeviceCheck.check(Arrays.equals(bytes,received.toByteArray()),"large file matches every byte after public tool transfer");
            Files.write(file,new byte[3]);var changed=tools.call("android.pull",Map.of("path",file.toString(),"offset",0,"length",1024,"fileId",fileId));ParityDeviceCheck.check("file_changed".equals(changed.get("code")),"changed file cannot continue an old download");
            System.out.println("FILE CHECK COMPLETE "+ParityDeviceCheck.checks);exit=0;
        }catch(Throwable error){error.printStackTrace(System.out);}
        finally{try{Files.deleteIfExists(file);}catch(Exception ignored){}}
        System.exit(exit);
    }
}
