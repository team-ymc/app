/* @ds-bundle: {"format":4,"namespace":"PaperTeacherDesignSystem_1a53a7","components":[{"name":"PaperStackMark","sourcePath":"components/brand/PaperStackMark.jsx"},{"name":"Button","sourcePath":"components/core/Button.jsx"},{"name":"IconButton","sourcePath":"components/core/IconButton.jsx"},{"name":"Badge","sourcePath":"components/feedback/Badge.jsx"},{"name":"Toast","sourcePath":"components/feedback/Toast.jsx"},{"name":"Tooltip","sourcePath":"components/feedback/Tooltip.jsx"},{"name":"Checkbox","sourcePath":"components/forms/Checkbox.jsx"},{"name":"Input","sourcePath":"components/forms/Input.jsx"},{"name":"Select","sourcePath":"components/forms/Select.jsx"},{"name":"Switch","sourcePath":"components/forms/Switch.jsx"},{"name":"GlobalNav","sourcePath":"components/navigation/GlobalNav.jsx"},{"name":"Tabs","sourcePath":"components/navigation/Tabs.jsx"},{"name":"Dialog","sourcePath":"components/overlays/Dialog.jsx"},{"name":"ArchivalFolio","sourcePath":"components/study/ArchivalFolio.jsx"},{"name":"PaperSheet","sourcePath":"components/study/PaperSheet.jsx"},{"name":"SelectionToolbar","sourcePath":"components/study/SelectionToolbar.jsx"},{"name":"StudentMessage","sourcePath":"components/study/StudentMessage.jsx"},{"name":"TeachingAction","sourcePath":"components/study/TeachingAction.jsx"},{"name":"TutorNotebook","sourcePath":"components/study/TutorNotebook.jsx"},{"name":"NotebookSection","sourcePath":"components/study/TutorNotebook.jsx"}],"sourceHashes":{"components/brand/PaperStackMark.jsx":"1a4bf989b044","components/core/Button.jsx":"37c1d0729ff3","components/core/IconButton.jsx":"1e23bed888d0","components/feedback/Badge.jsx":"f289f2a7cbf9","components/feedback/Toast.jsx":"cb73389f7ef5","components/feedback/Tooltip.jsx":"8f7722169e7f","components/forms/Checkbox.jsx":"e33408eb3dfa","components/forms/Input.jsx":"1fcfef5f722a","components/forms/Select.jsx":"f5fb4636237f","components/forms/Switch.jsx":"af900246b88b","components/navigation/GlobalNav.jsx":"bfee9c59e4c6","components/navigation/Tabs.jsx":"fd104f124009","components/overlays/Dialog.jsx":"b316af59261a","components/study/ArchivalFolio.jsx":"c3d3e81cb284","components/study/PaperSheet.jsx":"ec9fc3882c0c","components/study/SelectionToolbar.jsx":"12bb1916a592","components/study/StudentMessage.jsx":"700b35b89080","components/study/TeachingAction.jsx":"aecfee8c8a54","components/study/TutorNotebook.jsx":"26c20510b5d8"},"inlinedExternals":[],"unexposedExports":[]} */

(() => {

const __ds_ns = (window.PaperTeacherDesignSystem_1a53a7 = window.PaperTeacherDesignSystem_1a53a7 || {});

const __ds_scope = {};

(__ds_ns.__errors = __ds_ns.__errors || []);

// components/brand/PaperStackMark.jsx
try { (() => {
function PaperStackMark({
  size = 32,
  color = 'currentColor',
  style
}) {
  return React.createElement('svg', {
    width: size,
    height: size,
    viewBox: '0 0 32 32',
    fill: 'none',
    style
  }, React.createElement('rect', {
    x: 6,
    y: 10,
    width: 20,
    height: 16,
    rx: 1,
    stroke: color,
    strokeWidth: 1.5
  }), React.createElement('rect', {
    x: 4,
    y: 7,
    width: 20,
    height: 16,
    rx: 1,
    stroke: color,
    strokeWidth: 1.5,
    fill: 'var(--color-bg-paper, #FFFDF7)'
  }), React.createElement('path', {
    d: 'M4 7 H20 L24 11 V23 H4 Z',
    stroke: color,
    strokeWidth: 1.5,
    fill: 'var(--color-bg-paper, #FFFDF7)'
  }), React.createElement('path', {
    d: 'M20 7 V11 H24',
    stroke: color,
    strokeWidth: 1.5,
    fill: 'none'
  }), React.createElement('line', {
    x1: 8,
    y1: 15,
    x2: 18,
    y2: 15,
    stroke: color,
    strokeWidth: 1.2
  }), React.createElement('line', {
    x1: 8,
    y1: 18.5,
    x2: 15,
    y2: 18.5,
    stroke: color,
    strokeWidth: 1.2
  }));
}
Object.assign(__ds_scope, { PaperStackMark });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/brand/PaperStackMark.jsx", error: String((e && e.message) || e) }); }

// components/core/Button.jsx
try { (() => {
const VARIANTS = {
  primary: {
    background: 'var(--color-primary)',
    color: 'var(--color-on-primary)',
    border: '1px solid transparent',
    radius: 'var(--radius-pill)',
    padding: '11px 20px',
    height: '44px'
  },
  secondary: {
    background: 'var(--color-bg-paper)',
    color: 'var(--color-primary)',
    border: '1px solid var(--color-border)',
    radius: 'var(--radius-control)',
    padding: '10px 16px',
    height: '42px'
  },
  landing: {
    background: 'var(--color-bg-paper)',
    color: 'var(--color-primary)',
    border: '1px solid transparent',
    radius: 'var(--radius-pill)',
    padding: '13px 24px',
    height: '48px'
  },
  text: {
    background: 'transparent',
    color: 'var(--color-primary)',
    border: 'none',
    radius: 'var(--radius-none)',
    padding: '4px 2px',
    height: 'auto'
  }
};
const HOVER = {
  primary: 'var(--color-primary-hover)',
  secondary: 'var(--color-primary-subtle)',
  landing: 'var(--color-primary-subtle)',
  text: 'var(--color-primary-subtle)'
};
function Button({
  variant = 'primary',
  icon,
  disabled = false,
  type = 'button',
  children,
  onClick,
  style
}) {
  const v = VARIANTS[variant] || VARIANTS.primary;
  const [hover, setHover] = React.useState(false);
  return React.createElement('button', {
    type,
    disabled,
    onClick,
    onMouseEnter: () => setHover(true),
    onMouseLeave: () => setHover(false),
    style: {
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      gap: '8px',
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--ui-strong-size)',
      fontWeight: 'var(--ui-strong-weight)',
      letterSpacing: 'var(--ui-strong-tracking)',
      lineHeight: 1,
      background: variant === 'primary' && hover ? HOVER.primary : v.background,
      color: v.color,
      border: v.border,
      borderRadius: v.radius,
      padding: v.padding,
      height: v.height,
      cursor: disabled ? 'not-allowed' : 'pointer',
      opacity: disabled ? 0.5 : 1,
      textDecoration: variant === 'text' && hover ? 'underline' : 'none',
      borderColor: variant === 'secondary' && hover ? 'var(--color-primary)' : v.border,
      transition: 'background 150ms ease, border-color 150ms ease',
      ...style
    }
  }, icon ? React.createElement('i', {
    className: `ph ph-${icon}`,
    style: {
      fontSize: '16px'
    }
  }) : null, children);
}
Object.assign(__ds_scope, { Button });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Button.jsx", error: String((e && e.message) || e) }); }

// components/core/IconButton.jsx
try { (() => {
function IconButton({
  icon,
  label,
  selected = false,
  disabled = false,
  size = 40,
  onClick,
  style
}) {
  const [hover, setHover] = React.useState(false);
  return React.createElement('button', {
    'aria-label': label,
    title: label,
    disabled,
    onClick,
    onMouseEnter: () => setHover(true),
    onMouseLeave: () => setHover(false),
    style: {
      width: `${size}px`,
      height: `${size}px`,
      borderRadius: 'var(--radius-pill)',
      border: 'none',
      background: selected ? 'var(--color-primary-subtle)' : hover ? 'var(--color-primary-subtle)' : 'transparent',
      color: 'var(--color-primary)',
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      cursor: disabled ? 'not-allowed' : 'pointer',
      opacity: disabled ? 0.5 : 1,
      transition: 'background 150ms ease',
      ...style
    }
  }, React.createElement('i', {
    className: `ph ph-${icon}`,
    style: {
      fontSize: `${Math.round(size * 0.45)}px`
    }
  }));
}
Object.assign(__ds_scope, { IconButton });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/IconButton.jsx", error: String((e && e.message) || e) }); }

// components/feedback/Badge.jsx
try { (() => {
const TONES = {
  success: {
    background: 'var(--color-primary-subtle)',
    color: 'var(--success-sage)',
    border: '1px solid var(--success-sage)'
  },
  danger: {
    background: 'transparent',
    color: 'var(--color-danger)',
    border: '1px solid var(--color-danger)'
  },
  neutral: {
    background: 'var(--color-bg-surface)',
    color: 'var(--color-text-muted)',
    border: '1px solid var(--color-border)'
  }
};
function Badge({
  children,
  tone = 'neutral',
  icon,
  style
}) {
  const t = TONES[tone] || TONES.neutral;
  return React.createElement('span', {
    style: {
      display: 'inline-flex',
      alignItems: 'center',
      gap: '5px',
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--label-size)',
      fontWeight: 'var(--label-weight)',
      letterSpacing: 'var(--label-tracking)',
      textTransform: 'uppercase',
      padding: '4px 10px',
      borderRadius: 'var(--radius-pill)',
      ...t,
      ...style
    }
  }, icon ? React.createElement('i', {
    className: `ph ph-${icon}`,
    style: {
      fontSize: '12px'
    }
  }) : null, children);
}
Object.assign(__ds_scope, { Badge });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/Badge.jsx", error: String((e && e.message) || e) }); }

// components/feedback/Toast.jsx
try { (() => {
const TONES = {
  success: {
    icon: 'check-circle',
    color: 'var(--success-sage)'
  },
  danger: {
    icon: 'warning-circle',
    color: 'var(--color-danger)'
  },
  info: {
    icon: 'info',
    color: 'var(--color-primary)'
  }
};
function Toast({
  tone = 'info',
  children,
  onClose,
  style
}) {
  const t = TONES[tone] || TONES.info;
  return React.createElement('div', {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: '10px',
      background: 'var(--color-bg-paper)',
      border: '1px solid var(--color-border)',
      borderRadius: 'var(--radius-control)',
      padding: '12px 14px',
      boxShadow: 'var(--shadow-menu)',
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--ui-body-size)',
      color: 'var(--color-text-body)',
      maxWidth: '360px',
      ...style
    }
  }, React.createElement('i', {
    className: `ph ph-${t.icon}`,
    style: {
      fontSize: '18px',
      color: t.color,
      flexShrink: 0
    }
  }), React.createElement('span', {
    style: {
      flex: 1
    }
  }, children), onClose ? React.createElement('i', {
    className: 'ph ph-x',
    onClick: onClose,
    style: {
      fontSize: '14px',
      color: 'var(--color-text-muted)',
      cursor: 'pointer'
    }
  }) : null);
}
Object.assign(__ds_scope, { Toast });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/Toast.jsx", error: String((e && e.message) || e) }); }

// components/feedback/Tooltip.jsx
try { (() => {
function Tooltip({
  label,
  children
}) {
  const [open, setOpen] = React.useState(false);
  return React.createElement('span', {
    style: {
      position: 'relative',
      display: 'inline-flex'
    },
    onMouseEnter: () => setOpen(true),
    onMouseLeave: () => setOpen(false)
  }, children, open ? React.createElement('span', {
    style: {
      position: 'absolute',
      bottom: 'calc(100% + 8px)',
      left: '50%',
      transform: 'translateX(-50%)',
      background: 'var(--color-bg-walnut)',
      color: 'var(--color-on-dark)',
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--caption-size)',
      padding: '6px 10px',
      borderRadius: 'var(--radius-structural)',
      whiteSpace: 'nowrap',
      boxShadow: 'var(--shadow-menu)',
      zIndex: 10
    }
  }, label) : null);
}
Object.assign(__ds_scope, { Tooltip });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/Tooltip.jsx", error: String((e && e.message) || e) }); }

// components/forms/Checkbox.jsx
try { (() => {
function Checkbox({
  label,
  checked,
  onChange,
  style
}) {
  return React.createElement('label', {
    style: {
      display: 'inline-flex',
      alignItems: 'center',
      gap: '10px',
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--ui-body-size)',
      color: 'var(--color-text-body)',
      cursor: 'pointer',
      ...style
    }
  }, React.createElement('span', {
    onClick: () => onChange && onChange(!checked),
    style: {
      width: '18px',
      height: '18px',
      borderRadius: 'var(--radius-none)',
      border: `1.5px solid ${checked ? 'var(--color-primary)' : 'var(--color-border)'}`,
      background: checked ? 'var(--color-primary)' : 'var(--color-bg-paper)',
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      flexShrink: 0
    }
  }, checked ? React.createElement('i', {
    className: 'ph ph-check-bold',
    style: {
      fontSize: '12px',
      color: 'var(--color-on-primary)'
    }
  }) : null), label);
}
Object.assign(__ds_scope, { Checkbox });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Checkbox.jsx", error: String((e && e.message) || e) }); }

// components/forms/Input.jsx
try { (() => {
function Input({
  label,
  placeholder,
  value,
  onChange,
  error,
  type = 'text',
  style
}) {
  const [focus, setFocus] = React.useState(false);
  return React.createElement('div', {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: '6px',
      fontFamily: 'var(--font-sans)',
      ...style
    }
  }, label ? React.createElement('label', {
    style: {
      fontSize: 'var(--ui-strong-size)',
      fontWeight: 'var(--ui-strong-weight)',
      color: 'var(--color-text-heading)'
    }
  }, label) : null, React.createElement('input', {
    type,
    placeholder,
    value,
    onChange,
    style: {
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--ui-body-size)',
      color: 'var(--color-text-body)',
      background: 'var(--color-bg-paper)',
      border: `1px solid ${error ? 'var(--color-danger)' : focus ? 'var(--color-primary)' : 'var(--color-border)'}`,
      borderRadius: 'var(--radius-control)',
      padding: '11px 14px',
      height: '44px',
      outline: 'none'
    },
    onFocus: () => setFocus(true),
    onBlur: () => setFocus(false)
  }), error ? React.createElement('span', {
    style: {
      fontSize: 'var(--caption-size)',
      color: 'var(--color-danger)'
    }
  }, error) : null);
}
Object.assign(__ds_scope, { Input });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Input.jsx", error: String((e && e.message) || e) }); }

// components/forms/Select.jsx
try { (() => {
function Select({
  label,
  value,
  onChange,
  options = [],
  style
}) {
  return React.createElement('div', {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: '6px',
      fontFamily: 'var(--font-sans)',
      ...style
    }
  }, label ? React.createElement('label', {
    style: {
      fontSize: 'var(--ui-strong-size)',
      fontWeight: 'var(--ui-strong-weight)',
      color: 'var(--color-text-heading)'
    }
  }, label) : null, React.createElement('select', {
    value,
    onChange,
    style: {
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--ui-body-size)',
      color: 'var(--color-text-body)',
      background: 'var(--color-bg-paper)',
      border: '1px solid var(--color-border)',
      borderRadius: 'var(--radius-control)',
      padding: '11px 14px',
      height: '44px',
      outline: 'none'
    }
  }, options.map(o => React.createElement('option', {
    key: o.value,
    value: o.value
  }, o.label))));
}
Object.assign(__ds_scope, { Select });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Select.jsx", error: String((e && e.message) || e) }); }

// components/forms/Switch.jsx
try { (() => {
function Switch({
  checked,
  onChange,
  label,
  style
}) {
  return React.createElement('label', {
    style: {
      display: 'inline-flex',
      alignItems: 'center',
      gap: '10px',
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--ui-body-size)',
      color: 'var(--color-text-body)',
      cursor: 'pointer',
      ...style
    }
  }, React.createElement('span', {
    onClick: () => onChange && onChange(!checked),
    style: {
      width: '38px',
      height: '22px',
      borderRadius: 'var(--radius-pill)',
      background: checked ? 'var(--color-primary)' : 'var(--color-border)',
      position: 'relative',
      transition: 'background 150ms ease',
      flexShrink: 0
    }
  }, React.createElement('span', {
    style: {
      position: 'absolute',
      top: '2px',
      left: checked ? '18px' : '2px',
      width: '18px',
      height: '18px',
      borderRadius: '50%',
      background: 'var(--color-bg-paper)',
      transition: 'left 150ms ease'
    }
  })), label);
}
Object.assign(__ds_scope, { Switch });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Switch.jsx", error: String((e && e.message) || e) }); }

// components/navigation/GlobalNav.jsx
try { (() => {
function GlobalNav({
  children,
  mark,
  wordmark = 'Paper Teacher',
  right,
  style
}) {
  return React.createElement('header', {
    style: {
      height: '64px',
      background: 'var(--color-bg-walnut)',
      color: 'var(--color-on-dark)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: '0 24px',
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--ui-body-size)',
      ...style
    }
  }, React.createElement('div', {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: '10px'
    }
  }, mark, React.createElement('span', {
    style: {
      fontFamily: 'var(--font-serif)',
      fontWeight: 600,
      fontSize: '18px'
    }
  }, wordmark)), React.createElement('div', {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: '20px'
    }
  }, children), React.createElement('div', {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: '8px'
    }
  }, right));
}
Object.assign(__ds_scope, { GlobalNav });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/GlobalNav.jsx", error: String((e && e.message) || e) }); }

// components/navigation/Tabs.jsx
try { (() => {
function Tabs({
  items,
  value,
  onChange,
  style
}) {
  return React.createElement('div', {
    style: {
      display: 'flex',
      gap: '4px',
      borderBottom: '1px solid var(--color-border)',
      fontFamily: 'var(--font-sans)',
      ...style
    }
  }, items.map(it => {
    const active = it.value === value;
    return React.createElement('button', {
      key: it.value,
      onClick: () => onChange && onChange(it.value),
      style: {
        background: 'none',
        border: 'none',
        cursor: 'pointer',
        padding: '10px 4px',
        marginRight: '20px',
        fontSize: 'var(--ui-strong-size)',
        fontWeight: 'var(--ui-strong-weight)',
        color: active ? 'var(--color-primary)' : 'var(--color-text-muted)',
        borderBottom: active ? '2px solid var(--color-primary)' : '2px solid transparent',
        marginBottom: '-1px'
      }
    }, it.label);
  }));
}
Object.assign(__ds_scope, { Tabs });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/Tabs.jsx", error: String((e && e.message) || e) }); }

// components/overlays/Dialog.jsx
try { (() => {
function Dialog({
  open,
  title,
  children,
  actions,
  onClose
}) {
  if (!open) return null;
  return React.createElement('div', {
    style: {
      position: 'fixed',
      inset: 0,
      background: 'rgba(38,27,23,0.35)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 100
    },
    onClick: onClose
  }, React.createElement('div', {
    onClick: e => e.stopPropagation(),
    style: {
      background: 'var(--color-bg-paper)',
      border: '1px solid var(--color-border)',
      borderRadius: 'var(--radius-structural)',
      boxShadow: 'var(--shadow-menu)',
      padding: '28px',
      minWidth: '360px',
      maxWidth: '480px',
      fontFamily: 'var(--font-sans)'
    }
  }, title ? React.createElement('h2', {
    style: {
      fontFamily: 'var(--font-serif)',
      fontSize: 'var(--subheading-size)',
      fontWeight: 'var(--subheading-weight)',
      color: 'var(--color-text-heading)',
      margin: '0 0 12px'
    }
  }, title) : null, React.createElement('div', {
    style: {
      fontSize: 'var(--ui-body-size)',
      color: 'var(--color-text-body)',
      marginBottom: '20px'
    }
  }, children), React.createElement('div', {
    style: {
      display: 'flex',
      gap: '10px',
      justifyContent: 'flex-end'
    }
  }, actions)));
}
Object.assign(__ds_scope, { Dialog });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/overlays/Dialog.jsx", error: String((e && e.message) || e) }); }

// components/study/ArchivalFolio.jsx
try { (() => {
function ArchivalFolio({
  title,
  author,
  year,
  progress,
  thumbnail,
  style
}) {
  return React.createElement('div', {
    style: {
      background: 'var(--color-bg-surface)',
      border: '1px solid var(--color-border)',
      borderRadius: 'var(--radius-structural)',
      padding: '16px',
      fontFamily: 'var(--font-sans)',
      width: '200px',
      ...style
    }
  }, React.createElement('div', {
    style: {
      background: 'var(--color-bg-paper)',
      border: '1px solid var(--color-border)',
      borderRadius: 'var(--radius-paper)',
      height: '140px',
      marginBottom: '12px',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      overflow: 'hidden'
    }
  }, thumbnail ? React.createElement('img', {
    src: thumbnail,
    alt: title,
    style: {
      width: '100%',
      height: '100%',
      objectFit: 'cover'
    }
  }) : React.createElement('i', {
    className: 'ph ph-file-text',
    style: {
      fontSize: '32px',
      color: 'var(--color-text-muted)'
    }
  })), React.createElement('div', {
    style: {
      fontSize: 'var(--ui-strong-size)',
      fontWeight: 'var(--ui-strong-weight)',
      color: 'var(--color-text-heading)',
      marginBottom: '4px',
      lineHeight: 1.3
    }
  }, title), React.createElement('div', {
    style: {
      fontSize: 'var(--caption-size)',
      color: 'var(--color-text-muted)',
      marginBottom: '10px'
    }
  }, `${author} — ${year}`), progress != null ? React.createElement('div', {
    style: {
      height: '3px',
      background: 'var(--color-border)',
      borderRadius: 'var(--radius-pill)'
    }
  }, React.createElement('div', {
    style: {
      height: '100%',
      width: `${progress}%`,
      background: 'var(--color-primary)',
      borderRadius: 'var(--radius-pill)'
    }
  })) : null);
}
Object.assign(__ds_scope, { ArchivalFolio });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/study/ArchivalFolio.jsx", error: String((e && e.message) || e) }); }

// components/study/PaperSheet.jsx
try { (() => {
function PaperSheet({
  title,
  meta,
  children,
  style
}) {
  return React.createElement('article', {
    style: {
      background: 'var(--color-bg-paper)',
      borderRadius: 'var(--radius-paper)',
      boxShadow: 'var(--shadow-paper)',
      padding: '64px',
      maxWidth: '720px',
      margin: '0 auto',
      fontFamily: 'var(--font-serif)',
      ...style
    }
  }, title ? React.createElement('h1', {
    style: {
      fontSize: 'var(--paper-title-size)',
      fontWeight: 'var(--paper-title-weight)',
      lineHeight: 'var(--paper-title-line)',
      letterSpacing: 'var(--paper-title-tracking)',
      color: 'var(--color-text-heading)',
      margin: '0 0 8px'
    }
  }, title) : null, meta ? React.createElement('p', {
    style: {
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--caption-size)',
      color: 'var(--color-text-muted)',
      margin: '0 0 32px'
    }
  }, meta) : null, React.createElement('div', {
    style: {
      fontSize: 'var(--paper-body-size)',
      fontWeight: 'var(--paper-body-weight)',
      lineHeight: 'var(--paper-body-line)',
      color: 'var(--color-text-body)'
    }
  }, children));
}
Object.assign(__ds_scope, { PaperSheet });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/study/PaperSheet.jsx", error: String((e && e.message) || e) }); }

// components/study/SelectionToolbar.jsx
try { (() => {
function SelectionToolbar({
  onExplain,
  onAsk,
  onTranslate,
  style
}) {
  const items = [{
    icon: 'sparkle',
    label: 'Explain',
    onClick: onExplain
  }, {
    icon: 'chat-circle-text',
    label: 'Ask',
    onClick: onAsk
  }, {
    icon: 'translate',
    label: 'Translate',
    onClick: onTranslate
  }];
  return React.createElement('div', {
    style: {
      display: 'inline-flex',
      background: 'var(--color-bg-walnut)',
      borderRadius: 'var(--radius-control)',
      boxShadow: 'var(--shadow-menu)',
      padding: '4px',
      gap: '2px',
      ...style
    }
  }, items.map(it => React.createElement('button', {
    key: it.label,
    onClick: it.onClick,
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: '6px',
      background: 'transparent',
      border: 'none',
      cursor: 'pointer',
      color: 'var(--color-on-dark)',
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--caption-size)',
      fontWeight: 600,
      padding: '8px 12px',
      borderRadius: 'var(--radius-structural)'
    }
  }, React.createElement('i', {
    className: `ph ph-${it.icon}`,
    style: {
      fontSize: '14px'
    }
  }), it.label)));
}
Object.assign(__ds_scope, { SelectionToolbar });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/study/SelectionToolbar.jsx", error: String((e && e.message) || e) }); }

// components/study/StudentMessage.jsx
try { (() => {
function StudentMessage({
  children,
  style
}) {
  return React.createElement('div', {
    style: {
      display: 'inline-block',
      background: 'var(--color-bg-paper)',
      color: 'var(--color-primary)',
      border: '1px solid var(--color-primary)',
      borderRadius: 'var(--radius-message)',
      padding: '12px 14px',
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--ui-body-size)',
      lineHeight: 'var(--ui-body-line)',
      maxWidth: '80%',
      marginLeft: 'auto',
      ...style
    }
  }, children);
}
Object.assign(__ds_scope, { StudentMessage });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/study/StudentMessage.jsx", error: String((e && e.message) || e) }); }

// components/study/TeachingAction.jsx
try { (() => {
function TeachingAction({
  label,
  icon,
  onClick,
  style
}) {
  const [hover, setHover] = React.useState(false);
  return React.createElement('button', {
    onClick,
    onMouseEnter: () => setHover(true),
    onMouseLeave: () => setHover(false),
    style: {
      display: 'inline-flex',
      alignItems: 'center',
      gap: '6px',
      background: hover ? 'var(--color-primary-selection)' : 'var(--color-primary-subtle)',
      color: 'var(--color-primary)',
      border: 'none',
      borderRadius: 'var(--radius-pill)',
      padding: '9px 14px',
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--ui-strong-size)',
      fontWeight: 'var(--ui-strong-weight)',
      cursor: 'pointer',
      transition: 'background 150ms ease',
      ...style
    }
  }, icon ? React.createElement('i', {
    className: `ph ph-${icon}`,
    style: {
      fontSize: '15px'
    }
  }) : null, label);
}
Object.assign(__ds_scope, { TeachingAction });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/study/TeachingAction.jsx", error: String((e && e.message) || e) }); }

// components/study/TutorNotebook.jsx
try { (() => {
function TutorNotebook({
  children,
  composer,
  style
}) {
  return React.createElement('div', {
    style: {
      display: 'flex',
      flexDirection: 'column',
      height: '100%',
      background: 'var(--color-bg-paper)',
      borderLeft: '1px solid var(--color-border)',
      fontFamily: 'var(--font-sans)',
      ...style
    }
  }, React.createElement('div', {
    style: {
      flex: 1,
      overflowY: 'auto',
      padding: '20px',
      display: 'flex',
      flexDirection: 'column',
      gap: '18px'
    }
  }, children), composer ? React.createElement('div', {
    style: {
      padding: '16px',
      borderTop: '1px solid var(--color-border)'
    }
  }, composer) : null);
}
function NotebookSection({
  label,
  children
}) {
  return React.createElement('div', null, label ? React.createElement('div', {
    style: {
      fontFamily: 'var(--font-sans)',
      fontSize: 'var(--label-size)',
      fontWeight: 'var(--label-weight)',
      letterSpacing: 'var(--label-tracking)',
      textTransform: 'uppercase',
      color: 'var(--color-accent-brass)',
      marginBottom: '6px'
    }
  }, label) : null, React.createElement('div', {
    style: {
      fontFamily: 'var(--font-serif)',
      fontSize: 'var(--ui-body-size)',
      lineHeight: 'var(--paper-body-line)',
      color: 'var(--color-text-body)'
    }
  }, children));
}
Object.assign(__ds_scope, { TutorNotebook, NotebookSection });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/study/TutorNotebook.jsx", error: String((e && e.message) || e) }); }

__ds_ns.PaperStackMark = __ds_scope.PaperStackMark;

__ds_ns.Button = __ds_scope.Button;

__ds_ns.IconButton = __ds_scope.IconButton;

__ds_ns.Badge = __ds_scope.Badge;

__ds_ns.Toast = __ds_scope.Toast;

__ds_ns.Tooltip = __ds_scope.Tooltip;

__ds_ns.Checkbox = __ds_scope.Checkbox;

__ds_ns.Input = __ds_scope.Input;

__ds_ns.Select = __ds_scope.Select;

__ds_ns.Switch = __ds_scope.Switch;

__ds_ns.GlobalNav = __ds_scope.GlobalNav;

__ds_ns.Tabs = __ds_scope.Tabs;

__ds_ns.Dialog = __ds_scope.Dialog;

__ds_ns.ArchivalFolio = __ds_scope.ArchivalFolio;

__ds_ns.PaperSheet = __ds_scope.PaperSheet;

__ds_ns.SelectionToolbar = __ds_scope.SelectionToolbar;

__ds_ns.StudentMessage = __ds_scope.StudentMessage;

__ds_ns.TeachingAction = __ds_scope.TeachingAction;

__ds_ns.TutorNotebook = __ds_scope.TutorNotebook;

__ds_ns.NotebookSection = __ds_scope.NotebookSection;

})();
