package com.deviceagent.app;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import com.deviceagent.tools.Json;
import java.nio.file.*;
import java.util.*;

/** Read grants expose only completed snapshots; shell alone can stage or remove them. */
public final class ShareProvider extends ContentProvider {
 private Path root(){return getContext().getCacheDir().toPath().resolve("shares");}
 private void writer(){if(Binder.getCallingUid()!=2000)throw new SecurityException("Only shell can prepare a share");}
 private Path item(Uri uri){String id=uri.getLastPathSegment();if(id==null||!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid share");return root().resolve(id);}
 private Map<String,Object> meta(Uri uri)throws java.io.IOException{
  Path path=item(uri);var m=Json.object(new String(Files.readAllBytes(path.resolve("meta.json")),java.nio.charset.StandardCharsets.UTF_8));
  if(((Number)m.get("expiresAt")).longValue()<=System.currentTimeMillis())throw new java.io.FileNotFoundException("This shared file expired. Share it again.");return m;
 }
 @Override public boolean onCreate(){try{Files.createDirectories(root());sweep();return true;}catch(Exception e){return false;}}
 private void sweep()throws java.io.IOException{try(var dirs=Files.list(root())){for(Path p:dirs.collect(java.util.stream.Collectors.toList()))if(Files.getLastModifiedTime(p).toMillis()<System.currentTimeMillis()-86400000L)remove(p);}}
 private void remove(Path p)throws java.io.IOException{try(var paths=Files.walk(p)){for(Path f:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(f);}}
 @Override public synchronized Bundle call(String method,String argument,Bundle extras){writer();long identity=Binder.clearCallingIdentity();
  try{
   var a=Json.object(argument);Map<String,Object> out;
   if("create".equals(method)){
    sweep();
    String name=Json.string(a,"name"),mime=Json.string(a,"mimeType");if(name==null||name.isBlank()||name.length()>255||name.contains("/")||name.contains("\\")||mime==null||!mime.matches("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+"))throw new IllegalArgumentException("Use a file name and MIME type");
    String id=UUID.randomUUID().toString().replace("-","");Path dir=root().resolve(id);Files.createDirectory(dir);long expiry=System.currentTimeMillis()+86400000L;
    Files.write(dir.resolve("meta.json"),Json.stringify(Json.mapOf("name",name,"mimeType",mime,"expiresAt",expiry,"ready",false)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Files.createFile(dir.resolve("data"));out=Json.mapOf("ok",true,"uri","content://"+getContext().getPackageName()+".shares/"+id,"expiresAt",expiry);
   }else{
    Uri uri=Uri.parse(Json.string(a,"uri"));Path dir=item(uri);
    if("remove".equals(method)){getContext().revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);if(Files.exists(dir))remove(dir);out=Json.mapOf("ok",true);}
    else if("finish".equals(method)){
     var m=meta(uri);Path data=dir.resolve("data");long size=Files.size(data);
     var digest=java.security.MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(data)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}
     StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b));String hash=hex.toString();if(!hash.equals(a.get("sha256")))throw new IllegalStateException("The shared copy did not match the source");
     m.put("ready",true);m.put("size",size);m.put("sha256",hash);Files.write(dir.resolve("meta.json"),Json.stringify(m).getBytes(java.nio.charset.StandardCharsets.UTF_8));out=Json.mapOf("ok",true,"uri",uri.toString(),"bytes",size,"sha256",hash,"expiresAt",m.get("expiresAt"));
    }else throw new IllegalArgumentException("Unknown share operation");
   }
   Bundle result=new Bundle();result.putString("result",Json.stringify(out));return result;
  }catch(Exception e){Bundle b=new Bundle();b.putString("result",Json.stringify(Json.mapOf("ok",false,"error",e.getMessage()==null?"The shared file could not be prepared":e.getMessage())));return b;}
  finally{Binder.restoreCallingIdentity(identity);}
 }
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException{
  try{var m=meta(uri);if(!"r".equals(mode)){writer();if(Boolean.TRUE.equals(m.get("ready")))throw new SecurityException("Shared files cannot change");return ParcelFileDescriptor.open(item(uri).resolve("data").toFile(),ParcelFileDescriptor.MODE_WRITE_ONLY|ParcelFileDescriptor.MODE_TRUNCATE);}
   if(!Boolean.TRUE.equals(m.get("ready")))throw new java.io.FileNotFoundException("The shared file is not ready");return ParcelFileDescriptor.open(item(uri).resolve("data").toFile(),ParcelFileDescriptor.MODE_READ_ONLY);
  }catch(java.io.FileNotFoundException e){throw e;}catch(Exception e){throw new java.io.FileNotFoundException("The shared file is unavailable");}
 }
 @Override public String getType(Uri uri){try{return (String)meta(uri).get("mimeType");}catch(Exception e){return null;}}
 @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
  try{var m=meta(uri);String[] fields=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(fields);Object[] values=new Object[fields.length];for(int i=0;i<fields.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(fields[i])?m.get("name"):OpenableColumns.SIZE.equals(fields[i])?m.get("size"):null;c.addRow(values);return c;}catch(Exception e){return null;}
 }
 @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
 @Override public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
 @Override public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
