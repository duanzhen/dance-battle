package com.dance.street.game.domain.bo;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参赛单位 BO 的校验分组。
 *
 * <p>事故现象:赛段流程里给参赛方改名报「不能为空, 不能为空」。
 * 原因是 {@code PUT /game/competitor} 用 EditGroup 校验,而 tournamentId/stageId 也挂在
 * EditGroup 上;前端改名只传 {@code {id, name, syncPlayerName}},两个字段同时被判空,
 * 全局异常处理器把两条同名消息用逗号拼起来,就成了一句莫名其妙的「不能为空, 不能为空」。</p>
 *
 * <p>编辑走的是部分更新(updateById 忽略 null),赛事/赛段可由服务端按 id 反查,
 * 因此只要求新增时必填。</p>
 */
class TCompetitorBoValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /** 赛段选手列表的改名:只有 id + name,EditGroup 校验必须放行 */
    @Test
    void partialRenamePassesEditGroupValidation() {
        TCompetitorBo bo = new TCompetitorBo();
        bo.setId(1L);
        bo.setName("新名字");
        bo.setSyncPlayerName(false);

        assertTrue(VALIDATOR.validate(bo, EditGroup.class).isEmpty(),
            "改名只传 id+name 不该被拦下,实际: " + VALIDATOR.validate(bo, EditGroup.class));
    }

    /** 编辑仍必须给出主键,否则不知道改谁 */
    @Test
    void editStillRequiresId() {
        TCompetitorBo bo = new TCompetitorBo();
        bo.setName("新名字");

        assertEquals(1, VALIDATOR.validate(bo, EditGroup.class).size(), "缺少 id 应被拦下");
    }

    /** 新增仍要求赛事/赛段,避免建出挂在空赛段上的参赛方 */
    @Test
    void addStillRequiresTournamentAndStage() {
        TCompetitorBo bo = new TCompetitorBo();
        bo.setName("新选手");

        assertEquals(2, VALIDATOR.validate(bo, AddGroup.class).size(), "新增缺少赛事/赛段应被拦下");
        assertFalse(VALIDATOR.validate(bo, AddGroup.class).isEmpty());
    }
}
