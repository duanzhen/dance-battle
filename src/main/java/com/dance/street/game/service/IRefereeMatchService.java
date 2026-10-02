package com.dance.street.game.service;

import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.vo.MatchResultVo;
import com.dance.street.game.domain.vo.RefereeMatchVo;

/**
 * 裁判端比赛信息服务接口。
 *
 * <p>裁判端只有一个页面(判罚页),但它要的东西最多:当前该判哪一场、这一场都有谁、
 * 我自己打了多少分、本赛段还有哪些场次、多裁判判罚进度、以及"哪些圈我能判"的权限口径。
 * 这些读取与组装此前写在控制器里(400 余行),现在收敛到这里,控制器只做鉴权与转发。</p>
 *
 * @author duane
 */
public interface IRefereeMatchService {

    /**
     * 裁判端当前比赛信息:自动定位到该裁判名下正在进行的赛段与场次,也可由前端指定切换。
     *
     * @param tournamentId 赛事ID(来自裁判凭证)
     * @param refereeId    裁判ID
     * @param refereeName  裁判姓名(回显用)
     * @param stageId      指定赛段(可空,多赛段并行时前端切换)
     * @param matchId      指定场次(可空,多场并行时前端切换)
     * @return 判罚页所需的全部信息
     */
    RefereeMatchVo myMatch(Long tournamentId, Long refereeId, String refereeName, Long stageId, Long matchId);

    /**
     * 裁判提交打分/判罚。越权与圈级权限校验都在这里,控制器不再自己查库。
     *
     * @param matchId     场次ID
     * @param tournamentId 该裁判所属赛事ID(可为空,为空时跳过赛事归属校验)
     * @param refereeId   裁判ID
     * @param refereeName 裁判姓名(日志用)
     * @param bo          提交内容
     * @return 提交后的场次结果
     */
    MatchResultVo submitScore(Long matchId, Long tournamentId, Long refereeId, String refereeName,
                              SubmitResultBo bo);
}
