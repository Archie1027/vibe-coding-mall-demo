package com.zhicai.platform.catalog;

import com.zhicai.platform.catalog.CatalogModels.Category;
import com.zhicai.platform.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CategoryWriteServiceTest {
    final CategoryWriteMapper mapper = mock(CategoryWriteMapper.class);
    final CategoryWriteService service = new CategoryWriteService(mapper);
    @BeforeEach void tree() {
        when(mapper.lockAnchor()).thenReturn("1");
        when(mapper.tree()).thenReturn(List.of(node("1", null), node("2", "1"), node("3", "2"), node("4", null)));
        when(mapper.insert(any())).thenAnswer(call -> { ((CategoryWriteMapper.NewCategory) call.getArgument(0)).id = 55L; return 1; });
    }
    @Test void createsThirdLevelAndLocksBeforeReading() {
        var created = service.create("三级", "2", 5, true);
        assertThat(created.before()).isNull(); assertThat(created.after()).isEqualTo(new Category("55", "三级", "2", 5, true, 0));
        var order = inOrder(mapper); order.verify(mapper).lockAnchor(); order.verify(mapper).tree(); order.verify(mapper).insert(any());
    }
    @Test void creatingFourthLevelFailsBeforeInsert() {
        code(() -> service.create("四级", "3", 0, true), 409, "CATEGORY_DEPTH_EXCEEDED"); verify(mapper, never()).insert(any());
    }
    @Test void moveCannotPushExistingDescendantsBeyondThreeLevels() {
        code(() -> service.update("1", "根", "4", 0, true, 0L), 409, "CATEGORY_DEPTH_EXCEEDED");
        verify(mapper, never()).update(any(), any(), any(), anyInt(), anyBoolean(), anyLong());
    }
    @Test void rejectsSelfParentAndAncestorCycles() {
        code(() -> service.update("1", "根", "1", 0, true, 0L), 409, "CATEGORY_CYCLE");
        code(() -> service.update("1", "根", "3", 0, true, 0L), 409, "CATEGORY_CYCLE");
    }
    @Test void rejectsMissingParentAndResource() {
        code(() -> service.create("分类", "999", 0, true), 404, "NOT_FOUND");
        code(() -> service.update("999", "分类", null, 0, true, 0L), 404, "NOT_FOUND");
    }
    @Test void validMoveAndDisableKeepOriginalAndNewAuditStates() {
        when(mapper.update("2", "移到另一根", "4", 8, false, 0)).thenReturn(1);
        var changed = service.update("2", "移到另一根", "4", 8, false, 0L);
        assertThat(changed.before()).isEqualTo(node("2", "1"));
        assertThat(changed.after()).isEqualTo(new Category("2", "移到另一根", "4", 8, false, 1));
    }
    @Test void staleOrConcurrentlyChangedVersionCannotOverwrite() {
        code(() -> service.update("2", "分类", null, 0, true, 1L), 409, "VERSION_CONFLICT");
        code(() -> service.update("2", "分类", null, 0, true, 0L), 409, "VERSION_CONFLICT");
    }
    @Test void emptyDirectoryAcceptsRootsButNoUnprovenParent() {
        when(mapper.lockAnchor()).thenReturn(null);
        assertThat(service.create("首个根", null, 0, true).after().parentId()).isNull();
        code(() -> service.create("子分类", "1", 0, true), 404, "NOT_FOUND");
        verify(mapper, never()).tree();
    }
    @Test void invalidFieldsCannotAcquireTheTreeLock() {
        clearInvocations(mapper);
        for (String name : new String[]{null, " ", "a".repeat(121)})
            code(() -> service.create(name, null, 0, true), 400, "VALIDATION_ERROR");
        code(() -> service.create("分类", "01", 0, true), 400, "VALIDATION_ERROR");
        code(() -> service.create("分类", null, -1, true), 400, "VALIDATION_ERROR");
        code(() -> service.create("分类", null, 0, null), 400, "VALIDATION_ERROR");
        code(() -> service.update(null, "分类", null, 0, true, 0L), 400, "VALIDATION_ERROR");
        code(() -> service.update("1", "分类", null, 0, true, -1L), 400, "VALIDATION_ERROR");
        verifyNoInteractions(mapper);
    }
    private Category node(String id, String parent) { return new Category(id, "分类" + id, parent, 0, true, 0); }
    private void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status()).isEqualTo(status); assertThat(e.code()).isEqualTo(code); });
    }
}
