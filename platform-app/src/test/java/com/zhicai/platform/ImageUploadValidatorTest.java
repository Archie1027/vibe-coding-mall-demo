package com.zhicai.platform;

import com.zhicai.platform.media.ImageUploadValidator;
import com.zhicai.platform.common.ApiException;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;
import static org.assertj.core.api.Assertions.*;

class ImageUploadValidatorTest {
    final ImageUploadValidator validator = new ImageUploadValidator();
    @Test void actuallyDecodesJpegAndPngAndComputesFileFingerprint() throws Exception {
        for(String format : List.of("jpeg","png")) {
            byte[] bytes=image(format); var result=validator.validate(bytes,"image/"+format);
            assertThat(result.contentType()).isEqualTo("image/"+format); assertThat(result.bytes()).containsExactly(bytes);
            assertThat(result.sha256()).matches("[0-9a-f]{64}"); assertThat(result.extension()).isEqualTo(format.equals("jpeg")?"jpg":"png");
        }
    }
    @Test void clientMimeCannotDisguiseActualBytes() throws Exception {
        invalid(()->validator.validate(image("png"),"image/jpeg"),400);
        invalid(()->validator.validate("<html><script>evil()</script></html>".getBytes(),"image/png"),400);
        invalid(()->validator.validate(image("gif"),"image/gif"),400);
    }
    @Test void missingOrGenericClientMimeStillUsesDecodedFormat() throws Exception {
        assertThat(validator.validate(image("png"),null).contentType()).isEqualTo("image/png");
        assertThat(validator.validate(image("png"),"application/octet-stream").contentType()).isEqualTo("image/png");
    }
    @Test void rejectsEmptyTruncatedAndTooLargeBodies() throws Exception {
        invalid(()->validator.validate(new byte[0],"image/png"),400);
        invalid(()->validator.validate(Arrays.copyOf(image("png"),20),"image/png"),400);
        invalid(()->validator.validate(new byte[5*1024*1024+1],"image/png"),413);
    }
    @Test void rejectsJpegTruncatedAfterItsValidHeadersEvenIfDecoderReturnsPixels() throws Exception {
        var image=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<100;y++)for(int x=0;x<100;x++)image.setRGB(x,y,(x*255/99<<16)|(y*255/99<<8)|((x+y)*255/198));
        var out=new ByteArrayOutputStream();ImageIO.write(image,"jpeg",out);byte[] complete=out.toByteArray();
        assertThat(validator.validate(complete,"image/jpeg").contentType()).isEqualTo("image/jpeg");
        for(int trim:new int[]{2,20,100})invalid(()->validator.validate(Arrays.copyOf(complete,complete.length-trim),"image/jpeg"),400);
    }
    @Test void pixelBudgetIsCheckedBeforeImageAllocation() throws Exception {
        byte[] png=image("png");ByteBuffer.wrap(png).putInt(16,32000).putInt(20,32000);
        CRC32 crc=new CRC32();crc.update(png,12,17);ByteBuffer.wrap(png).putInt(29,(int)crc.getValue());
        assertThatThrownBy(()->validator.validate(png,"image/png")).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(400);assertThat(e.getMessage()).contains("1600");});
    }
    @Test void decodesActualWebpRatherThanOnlyItsRiffHeader() {
        byte[] webp=Base64.getDecoder().decode("UklGRhICAABXRUJQVlA4WAoAAAAgAAAAAQAAAQAASUNDUMgBAAAAAAHIAAAAAAQwAABtbnRyUkdCIFhZWiAH4AABAAEAAAAAAABhY3NwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAA9tYAAQAAAADTLQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAlkZXNjAAAA8AAAACRyWFlaAAABFAAAABRnWFlaAAABKAAAABRiWFlaAAABPAAAABR3dHB0AAABUAAAABRyVFJDAAABZAAAAChnVFJDAAABZAAAAChiVFJDAAABZAAAAChjcHJ0AAABjAAAADxtbHVjAAAAAAAAAAEAAAAMZW5VUwAAAAgAAAAcAHMAUgBHAEJYWVogAAAAAAAAb6IAADj1AAADkFhZWiAAAAAAAABimQAAt4UAABjaWFlaIAAAAAAAACSgAAAPhAAAts9YWVogAAAAAAAA9tYAAQAAAADTLXBhcmEAAAAAAAQAAAACZmYAAPKnAAANWQAAE9AAAApbAAAAAAAAAABtbHVjAAAAAAAAAAEAAAAMZW5VUwAAACAAAAAcAEcAbwBvAGcAbABlACAASQBuAGMALgAgADIAMAAxADZWUDggJAAAAHABAJ0BKgIAAgABQCYloAJ0AclgAOlhB/5+Z/8e//s/rAAAAA==");
        var result=validator.validate(webp,"image/webp");assertThat(result.extension()).isEqualTo("webp");assertThat(result.contentType()).isEqualTo("image/webp");
        invalid(()->validator.validate(Arrays.copyOf(webp,32),"image/webp"),400);
    }
    public static byte[] image(String format) throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),format,out);return out.toByteArray();}
    void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,int status){assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(status));}
}
