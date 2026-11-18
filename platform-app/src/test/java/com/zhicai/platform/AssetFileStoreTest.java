package com.zhicai.platform;

import com.zhicai.platform.media.*;
import com.zhicai.platform.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class AssetFileStoreTest {
    @TempDir Path directory;
    @Test void savesOnlyGeneratedKeysAndNeverOverwritesAnotherFile() throws Exception {
        var store=new AssetFileStore(directory.toString());var image=new ImageUploadValidator().validate(ImageUploadValidatorTest.image("png"),"image/png");
        String first=store.save(image),second=store.save(image);assertThat(first).matches("upload-[0-9a-f]{32}\\.png");assertThat(first).isNotEqualTo(second);
        assertThat(Files.readAllBytes(store.path(first))).containsExactly(image.bytes());store.remove(first);assertThat(Files.exists(store.path(second))).isTrue();
    }
    @Test void rejectsTraversalWrongKeysAndMissingConfiguration() {
        var store=new AssetFileStore(directory.toString());
        for(String key:new String[]{"../outside.png","upload-../../test.png","arbitrary.png","/tmp/file.png"})assertThatThrownBy(()->store.path(key)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->new AssetFileStore("").ensureConfigured()).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(503));
    }
    @Test void refusesASymlinkAsAnUploadedFile() throws Exception {
        var store=new AssetFileStore(directory.toString());String key="upload-"+"a".repeat(32)+".png";Path outside=directory.resolve("original.png");Files.write(outside,new byte[]{1,2});
        try { Files.createSymbolicLink(directory.resolve(key),outside); }
        catch (java.nio.file.FileSystemException unsupported) { org.junit.jupiter.api.Assumptions.abort("OS does not permit symbolic links"); }
        assertThatThrownBy(()->store.path(key)).isInstanceOf(ApiException.class);assertThat(Files.readAllBytes(outside)).containsExactly((byte)1,(byte)2);
    }
}
