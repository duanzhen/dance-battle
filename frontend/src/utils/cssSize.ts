/**
 * 归一化 CSS 尺寸值(字号/宽高等)。
 *
 * <p>组件的 dataConfig 里字号历史上既可能是数字(新建文本组件时写的是 {@code fontSize: 24}),
 * 也可能是字符串({@code '24px'} / {@code '1.5rem'} / {@code '5vh'})。数字直接塞进 style
 * 会生成 {@code font-size: 24}——<b>无单位,浏览器判定为非法值并忽略</b>,
 * 现场表现就是"字号配置改了没反应"。</p>
 *
 * <p>这里统一:纯数字按 px 处理,其余(带单位/百分比/calc 等)原样透传;空值回退默认值。</p>
 */
export const cssSize = (value: unknown, fallback: string): string => {
  if (value == null) {
    return fallback;
  }
  const text = String(value).trim();
  if (text === '') {
    return fallback;
  }
  // 纯数字(整数/小数)按 px 处理:24 → 24px
  if (/^\d+(\.\d+)?$/.test(text)) {
    return `${text}px`;
  }
  return text;
};
