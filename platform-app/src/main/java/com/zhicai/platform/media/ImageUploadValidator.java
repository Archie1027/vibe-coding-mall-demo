package com.zhicai.platform.media;

import com.zhicai.platform.common.ApiException;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

@Component
public class ImageUploadValidator {
    public static final int MAX_BYTES=5*1024*1024;
    public record Validated(byte[] bytes,String contentType,String extension,String sha256) {}
    public Validated validate(byte[] bytes,String declaredType) {
        if(bytes==null || bytes.length==0) invalid("请选择有效图片");
        if(bytes.length>MAX_BYTES) throw new ApiException(413,"PAYLOAD_TOO_LARGE","单张图片不能超过5MiB");
        ImageReader reader=null;
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext()) invalid("无法识别图片文件");reader=readers.next();
            boolean[] warned={false};reader.addIIOReadWarningListener((source,warning)->warned[0]=true);
            String extension=switch(reader.getFormatName().toLowerCase(Locale.ROOT)){case "jpeg","jpg"->"jpg";case "png"->"png";case "webp"->"webp";default->null;};
            if(extension==null) invalid("仅支持JPEG、PNG、WebP图片");
            String type=extension.equals("jpg")?"image/jpeg":"image/"+extension;
            if(declaredType!=null && !declaredType.isBlank() && !declaredType.equals("application/octet-stream") && !declaredType.equalsIgnoreCase(type)) invalid("声明的图片类型与实际文件不符");
            reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
            if(width<=0 || height<=0 || (long)width*height>16_000_000L) invalid("图片最多1600万像素，请先缩小尺寸");
            var image=reader.read(0);if(image==null || image.getWidth()!=width || image.getHeight()!=height) invalid("图片无法完整解码");image.flush();
            if(warned[0]) invalid("图片文件不完整或解码存在警告，请重新导出图片");
            return new Validated(bytes,type,extension,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }catch(ApiException error){throw error;}
        catch(IOException | RuntimeException error){throw new ApiException(400,"INVALID_IMAGE","图片文件损坏或无法解码");}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
        finally{if(reader!=null)reader.dispose();}
    }
    private static void invalid(String message){throw new ApiException(400,"INVALID_IMAGE",message);}
}
