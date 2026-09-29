import postcss from 'postcss';

/**
 * 把 `:hover` 规则统一包进 `@media (hover: hover) and (pointer: fine)`。
 *
 * 背景:触屏设备(Safari/Chrome)在手指点按后会持续命中 `:hover`,直到点别处
 * 才解除。表现为「按钮/选项卡点一下,颜色就卡在悬停色」,而且常和 focus 叠加,
 * 看起来像是聚焦导致的配色错误。
 *
 * 修复:让悬停样式只作用于真正具备悬停能力的精确指针设备,触屏端自然回到基础色。
 * `:active`(按下)与 `:focus-visible`(键盘导航)不受影响,触屏仍有按压反馈。
 *
 * 这里在 PostCSS 层统一处理,可同时覆盖三类来源:
 *   - UnoCSS 的 `hover:` 工具类(编译后是裸 `:hover`);
 *   - Element Plus 组件样式(如 `.el-button:hover`);
 *   - 各 SFC 里手写的 scoped `:hover`。
 *
 * 选择器含逗号时按括号深度拆分:只包裹带 `:hover` 的那部分,
 * 同一规则里不带 `:hover` 的选择器分支保持原样(不会在触屏上被误关)。
 */

const HOVER_QUERY = '(hover: hover) and (pointer: fine)';

/** 按顶层逗号拆分选择器,忽略括号/中括号内的逗号(如 `:not(a, b)`) */
function splitSelector(selector) {
  const parts = [];
  let depth = 0;
  let current = '';
  for (const ch of selector) {
    if (ch === '(' || ch === '[') {
      depth++;
    } else if (ch === ')' || ch === ']') {
      depth--;
    }
    if (ch === ',' && depth === 0) {
      parts.push(current);
      current = '';
    } else {
      current += ch;
    }
  }
  parts.push(current);
  return parts;
}

/** 该节点是否已处在带 hover 条件的 @media 里(避免重复包裹) */
function insideHoverMedia(node) {
  let parent = node.parent;
  while (parent) {
    if (parent.type === 'atrule' && parent.name === 'media' && /hover/.test(parent.params)) {
      return true;
    }
    parent = parent.parent;
  }
  return false;
}

/** 该节点是否在 @keyframes 内(其中的选择器不是 CSS 选择器) */
function insideKeyframes(node) {
  let parent = node.parent;
  while (parent) {
    if (parent.type === 'atrule' && /keyframes$/i.test(parent.name)) {
      return true;
    }
    parent = parent.parent;
  }
  return false;
}

export default function gateHover() {
  return {
    postcssPlugin: 'gate-hover',
    OnceExit(root) {
      const rules = [];
      root.walkRules((rule) => rules.push(rule));

      for (const rule of rules) {
        if (!rule.selector || !rule.selector.includes(':hover')) continue;
        if (!rule.parent || insideHoverMedia(rule) || insideKeyframes(rule)) continue;

        const parts = splitSelector(rule.selector);
        const hoverParts = parts.filter((part) => part.includes(':hover'));
        const restParts = parts.filter((part) => !part.includes(':hover'));
        if (!hoverParts.length) continue;

        const media = postcss.atRule({ name: 'media', params: HOVER_QUERY });

        // 保留不带 :hover 的分支(任何时候都生效),插在原位置之前
        if (restParts.length) {
          rule.parent.insertBefore(rule, rule.clone({ selector: restParts.join(',') }));
        }

        // 带 :hover 的分支移入媒体查询
        rule.selector = hoverParts.join(',');
        rule.parent.insertBefore(rule, media);
        rule.parent.removeChild(rule);
        media.append(rule);
      }
    }
  };
}

gateHover.postcss = true;
