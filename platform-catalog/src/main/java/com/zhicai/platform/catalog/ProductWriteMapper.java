package com.zhicai.platform.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ProductWriteMapper {
    String lockProduct(@Param("id") String id);
    Boolean categoryEnabled(@Param("id") String id);
    Boolean brandEnabled(@Param("id") String id);
    List<String> publicAssets(@Param("ids") List<String> ids);
    int insertProduct(NewProduct product);
    int updateProduct(@Param("id") String id, @Param("input") ProductWriteService.Input input, @Param("version") long version);
    int updateState(@Param("id") String id, @Param("state") String state, @Param("version") long version);
    int deleteImages(@Param("id") String id);
    int insertImage(@Param("id") String id, @Param("assetId") String assetId, @Param("sort") int sort);
    CatalogMapper.SkuRow findSku(@Param("id") String id);
    List<CatalogMapper.SkuRow> skus(@Param("id") String productId);
    int insertSku(NewSku sku);
    int updateSku(@Param("id") String id, @Param("input") ProductWriteService.SkuInput input, @Param("specsJson") String specsJson,
                  @Param("specsHash") String specsHash, @Param("version") long version);
    final class NewProduct {
        public Long id;
        public final ProductWriteService.Input input;
        public NewProduct(ProductWriteService.Input input) { this.input = input; }
    }
    final class NewSku {
        public Long id;
        public final String productId, specsJson, specsHash;
        public final ProductWriteService.SkuInput input;
        public NewSku(String productId, ProductWriteService.SkuInput input, String specsJson, String specsHash) {
            this.productId = productId; this.input = input; this.specsJson = specsJson; this.specsHash = specsHash;
        }
    }
}
