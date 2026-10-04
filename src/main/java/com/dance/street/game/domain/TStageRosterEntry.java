package com.dance.street.game.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * 赛段中间层名单 t_stage_roster_entry:一行 = 一个座位。
 *
 * <p>两个赛段之间的"下一赛段名单"就是这张表的行,它只有一份(没有批次、没有历史版本):
 * 谁进来、坐哪个位、什么性质全在这里;人工调整也直接增删改这些行,不再有 delta 表。</p>
 *
 * <p><b>空位是实体行</b>:座位号 1..N 连续,缺人= {@code slot_kind=BYE}、等上游=
 * {@code slot_kind=PENDING}。任何读路径都按 {@code slot} 取位,不许把"有人的行"重新编号压紧,
 * 否则轮空位会消失、后面的人整体前移(与 {@code t_match_participant.slot_kind} 同口径)。</p>
 *
 * @author duane
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_stage_roster_entry")
public class TStageRosterEntry extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    /** 赛事ID */
    private Long tournamentId;

    /** 中间层归属:这条入边指向的赛段 */
    private Long targetStageId;

    /** 座位号 1..N,连续不压紧 */
    private Long slot;

    /**
     * 来源赛段给出的<b>原座号</b>(备份):物化/实时落座时来源给这个人安排的座位,
     * 人工拖动只改 {@link #slot},不动这里。
     *
     * <p>实时对账靠它判断"来源是否真的改了这个人的座号":原座号没变 → 这一行原地不动
     * (导播拖过也不动);原座号变了(重判/名次变化) → 才把这个人的行摘下来重新落座。
     * 没有这份备份就只能拿当前座位号比,人工调整过的行会被误判、进而顶替到别人。</p>
     */
    private Long sourceSlot;

    /** PLAYER=有人 / BYE=轮空空位 / PENDING=等上游填入 */
    private String slotKind;

    /** SOURCE=来自来源赛段 / GUEST=外卡;空位行(纯 BYE/PENDING)为 null */
    private String refType;

    /** refType=SOURCE 时指向来源赛段的参赛方行 */
    private Long sourceCompetitorId;

    /** 直接来源赛段 */
    private Long sourceStageId;

    /**
     * 这个人是从哪条入边进来的({@code t_stage_roster_group.id})。
     *
     * <p>同一来源赛段可能有多条平行边(按圈 ZONE-k、按名次段),只知道 {@link #sourceStageId}
     * 无法区分;记下边 ID 后,汇合段/人工落座/实时对账都能追溯到具体入边。</p>
     */
    private Long sourceGroupId;

    /** refType=GUEST 时关联的选手 */
    private Long playerId;

    /** refType=GUEST 时的展示名 */
    private String guestName;

    /** refType=GUEST 时的类型(0:个人 1:队伍) */
    private Long guestType;

    /** refType=GUEST 时的号码(空=物化时自动生成) */
    private String guestNumber;

    /** 入场性质:ADVANCE/REVIVE/GUEST/MANUAL/CHECKIN,物化时写入参赛方 */
    private String entryTag;

    /** RULE=规则生成 / MANUAL=人工加进来的(只用于提示与显示,不参与合并) */
    private String origin;

    /** READY=可用 / PENDING=来源未结算 */
    private String status;

    /** 物化到目标层后回填的参赛方ID */
    private Long competitorId;

    /** 备注 */
    private String remark;
}
