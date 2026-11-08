// כל הקואורדינטות יחסיות לתמונה המוצגת, בלי השוליים. המחווה נשלחת בשלמותה רק בשחרור.
export function fittedRect(box, width, height) {
  const scale = Math.min(box.width / width, box.height / height);
  return {
    left: box.left + (box.width - width * scale) / 2,
    top: box.top + (box.height - height * scale) / 2,
    width: width * scale,
    height: height * scale,
  };
}
export const clamp = (value) => Math.max(0, Math.min(1, value));
export function pointIn(rect, x, y, inside = false) {
  if (
    rect.width <= 0 ||
    rect.height <= 0 ||
    (inside &&
      (x < rect.left || y < rect.top || x > rect.left + rect.width || y > rect.top + rect.height))
  )
    return null;
  return [clamp((x - rect.left) / rect.width), clamp((y - rect.top) / rect.height)];
}
export function pointerRequest(geometry, kind, points, duration = 60, dx = 0, dy = 0) {
  if (
    !geometry ||
    !Number.isInteger(geometry.displayWidth) ||
    geometry.displayWidth < 1 ||
    !Number.isInteger(geometry.displayHeight) ||
    geometry.displayHeight < 1 ||
    ![0, 1, 2, 3].includes(geometry.rotation)
  )
    return null;
  return {
    displayWidth: geometry.displayWidth,
    displayHeight: geometry.displayHeight,
    rotation: geometry.rotation,
    kind,
    points,
    duration: Math.max(40, Math.min(1500, Math.round(duration))),
    dx: Math.max(-1, Math.min(1, dx)),
    dy: Math.max(-1, Math.min(1, dy)),
  };
}

export class PointerSurface {
  constructor({
    surface, image, cursor, direct, pad, drag, click, up, down, hint, enabled,
    canDrag = () => true, send,
  }) {
    Object.assign(this, {
      surface,
      image,
      cursor,
      direct,
      pad,
      drag,
      click,
      up,
      down,
      hint,
      enabled,
      canDrag,
      send,
    });
    this.position = [0.5, 0.5];
    this.contacts = new Map();
    this.path = [];
    this.mode = 'direct';
    this.armed = false;
    try {
      this.mode = localStorage.getItem('karov-pointer-mode') === 'pad' ? 'pad' : 'direct';
    } catch {}
    direct.onclick = () => this.setMode('direct');
    pad.onclick = () => this.setMode('pad');
    drag.onclick = () => {
      if (!this.canDrag()) return;
      this.armed = !this.armed;
      this.cancel();
      this.render();
    };
    click.onclick = () => this.submit('tap', [this.position.slice()]);
    up.onclick = () => this.scroll(0, -0.65);
    down.onclick = () => this.scroll(0, 0.65);
    surface.addEventListener('pointerdown', (e) => this.start(e));
    surface.addEventListener('pointermove', (e) => this.move(e));
    surface.addEventListener('pointerup', (e) => this.end(e));
    surface.addEventListener('pointercancel', () => this.cancel());
    surface.addEventListener('lostpointercapture', () => this.cancel());
    surface.addEventListener('pointerleave', () => {
      if (!this.contacts.size) this.hover = null;
    });
    surface.addEventListener('dragstart', (e) => e.preventDefault());
    surface.addEventListener(
      'wheel',
      (e) => {
        if (!this.ready() || e.target.closest('button')) return;
        e.preventDefault();
        const factor = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? this.rect().height : 1;
        this.wheelX = (this.wheelX || 0) + e.deltaX * factor;
        this.wheelY = (this.wheelY || 0) + e.deltaY * factor;
        if (!this.wheelTimer)
          this.wheelTimer = setTimeout(() => {
            this.wheelTimer = null;
            const x = this.wheelX,
              y = this.wheelY;
            this.wheelX = this.wheelY = 0;
            this.scroll(x / 300, y / 300);
          }, 100);
      },
      { passive: false },
    );
    surface.addEventListener('contextmenu', (e) => {
      if (this.ready()) e.preventDefault();
    });
    window.addEventListener('blur', () => this.cancel());
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) this.cancel();
    });
    this.resize = new ResizeObserver(() => {
      this.cancel();
      this.render();
    });
    this.resize.observe(surface);
    this.render();
  }
  ready() {
    return !!this.geometry && this.enabled() && !this.image.hidden && this.image.naturalWidth > 0;
  }
  rect() {
    return fittedRect(
      this.image.getBoundingClientRect(),
      this.image.naturalWidth || 16,
      this.image.naturalHeight || 9,
    );
  }
  frame(value) {
    const key = `${value.displayWidth}:${value.displayHeight}:${value.rotation}`;
    if (this.geometryKey !== key) this.cancel();
    this.geometryKey = key;
    this.geometry = pointerRequest(value, 'tap', [[0.5, 0.5]]) ? value : null;
    this.render();
  }
  clear() {
    this.geometry = null;
    this.cancel();
    this.render();
  }
  cancel() {
    this.contacts.clear();
    this.path = [];
    this.scrolling = false;
    this.started = 0;
    this.hover = null;
    clearTimeout(this.wheelTimer);
    this.wheelTimer = null;
    this.wheelX = this.wheelY = 0;
  }
  setMode(mode) {
    this.mode = mode;
    this.armed = false;
    this.cancel();
    try {
      localStorage.setItem('karov-pointer-mode', mode);
    } catch {}
    this.render();
  }
  render() {
    if (!this.canDrag()) this.armed = false;
    this.direct.setAttribute('aria-pressed', String(this.mode === 'direct'));
    this.pad.setAttribute('aria-pressed', String(this.mode === 'pad'));
    this.drag.setAttribute('aria-pressed', String(this.armed));
    this.drag.disabled = !this.ready() || this.mode !== 'pad' || !this.canDrag();
    for (const button of [this.click, this.up, this.down]) button.disabled = !this.ready();
    this.hint.textContent =
      !this.canDrag()
        ? 'נגיעה ללחיצה · גלגלת או שתי אצבעות לגלילה · גרירה ושליטה חופשית דורשות הפעלת שליטה נוספת בסטרימר'
        : this.mode === 'direct'
          ? 'לחיצה וגרירה על התמונה · גלגלת או שתי אצבעות לגלילה · גרירה נשלחת בשחרור'
          : 'הזזה על התמונה מזיזה את הסמן · נגיעה קצרה ללחיצה · גרירה: הפעילו את כפתור הגרירה';
    this.surface.classList.toggle('pointer-ready', this.ready());
    this.cursor.hidden = !this.ready();
    if (this.ready()) {
      const rect = this.rect(),
        box = this.surface.getBoundingClientRect();
      this.cursor.style.left = `${rect.left - box.left + this.position[0] * rect.width}px`;
      this.cursor.style.top = `${rect.top - box.top + this.position[1] * rect.height}px`;
      this.cursor.classList.toggle('dragging', this.armed);
    }
  }
  record() {
    if (this.path.length >= 64) this.path.splice(1, 1);
    this.path.push(this.position.slice());
  }
  start(e) {
    if (!this.ready() || e.button !== 0 || e.target.closest('button')) return;
    const point = pointIn(this.rect(), e.clientX, e.clientY, true);
    if (!point) return;
    e.preventDefault();
    this.surface.setPointerCapture(e.pointerId);
    this.contacts.set(e.pointerId, [e.clientX, e.clientY]);
    if (this.contacts.size === 1) {
      this.started = performance.now();
      this.startPoint = [e.clientX, e.clientY];
      this.last = [e.clientX, e.clientY];
      this.moved = false;
      this.path = [];
      this.scrolling = false;
      if (this.mode === 'direct') this.position = point;
      this.record();
    } else {
      this.scrolling = true;
      this.path = [];
      this.scrollX = this.scrollY = 0;
      this.last = this.center();
    }
    this.render();
  }
  center() {
    const values = [...this.contacts.values()];
    return values.reduce(
      (sum, p) => [sum[0] + p[0] / values.length, sum[1] + p[1] / values.length],
      [0, 0],
    );
  }
  move(e) {
    if (!this.ready()) {
      this.cancel();
      return;
    }
    const rect = this.rect();
    if (!this.contacts.has(e.pointerId)) {
      if (
        e.pointerType === 'mouse' &&
        pointIn(rect, e.clientX, e.clientY, true) &&
        !e.target.closest('button')
      ) {
        if (this.mode === 'direct') this.position = pointIn(rect, e.clientX, e.clientY);
        else if (this.hover)
          this.position = [
            clamp(this.position[0] + (e.clientX - this.hover[0]) / rect.width),
            clamp(this.position[1] + (e.clientY - this.hover[1]) / rect.height),
          ];
        this.hover = [e.clientX, e.clientY];
        this.render();
      }
      return;
    }
    e.preventDefault();
    this.contacts.set(e.pointerId, [e.clientX, e.clientY]);
    if (this.scrolling) {
      const center = this.center();
      this.scrollX -= (center[0] - this.last[0]) / rect.width;
      this.scrollY -= (center[1] - this.last[1]) / rect.height;
      this.last = center;
      return;
    }
    if (Math.hypot(e.clientX - this.startPoint[0], e.clientY - this.startPoint[1]) > 6)
      this.moved = true;
    this.position =
      this.mode === 'direct'
        ? pointIn(rect, e.clientX, e.clientY)
        : [
            clamp(this.position[0] + (e.clientX - this.last[0]) / rect.width),
            clamp(this.position[1] + (e.clientY - this.last[1]) / rect.height),
          ];
    this.last = [e.clientX, e.clientY];
    if (this.mode === 'direct' || this.armed) this.record();
    this.render();
  }
  end(e) {
    if (!this.contacts.has(e.pointerId)) return;
    e.preventDefault();
    if (this.scrolling) {
      if (Math.abs(this.scrollX) + Math.abs(this.scrollY) > 0.015)
        this.scroll(this.scrollX * 2, this.scrollY * 2);
    } else {
      this.move(e);
      const duration = performance.now() - this.started;
      if (!this.moved) this.submit('tap', [this.position.slice()], duration >= 500 ? 650 : 60);
      else if (this.mode === 'direct' || this.armed)
        this.submit(
          'drag',
          this.path.map((p) => p.slice()),
          Math.max(100, duration),
        );
    }
    this.cancel();
    this.render();
  }
  scroll(dx, dy) {
    if (Math.abs(dx) + Math.abs(dy) < 0.001) return;
    this.submit('scroll', [this.position.slice()], 300, dx, dy);
  }
  async submit(kind, points, duration = 60, dx = 0, dy = 0) {
    if (!this.ready() || this.sending) return;
    if (kind === 'drag' && !this.canDrag()) return;
    const request = pointerRequest(this.geometry, kind, points, duration, dx, dy);
    if (!request) return;
    this.sending = true;
    try {
      await this.send(request);
    } finally {
      this.sending = false;
    }
  }
}
