package com.zhicai.platform;

import com.zhicai.platform.identity.AuthService;
import com.zhicai.platform.security.SecurityConfiguration;
import com.zhicai.platform.web.AuthController;
import com.zhicai.platform.web.ErrorResponder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, ErrorResponder.class})
class CatalogMediaSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;

    @Test void productPhotosArePublicAndKeepTheirActualMediaTypes() throws Exception {
        mvc.perform(get("/assets/products/rtx-4080-super.jpg"))
            .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        mvc.perform(get("/assets/products/samsung-980-1tb.png"))
            .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/assets/products/rog-strix-scope.webp"))
            .andExpect(status().isOk()).andExpect(content().contentType("image/webp"));
    }

    @Test void unrelatedAssetPathsAndProductAssetWritesRemainProtected() throws Exception {
        mvc.perform(get("/assets/private/report.csv")).andExpect(status().isUnauthorized());
        mvc.perform(post("/assets/products/rtx-4080-super.jpg")).andExpect(status().isUnauthorized());
    }
}
