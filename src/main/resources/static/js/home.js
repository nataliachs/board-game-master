"use strict";
// Home page presentation: café scene, 3D shelf, scroll track and the ask-the-host chat.
// Games come from the server-rendered radio list; the backend still validates and creates the
// session, and rule answers come from the existing question endpoint. Without JavaScript the
// plain game list and the start form keep working.
// The page's CSP forbids inline styles, so styling goes through classes and the CSSOM only.
(() => {
  const $ = id => document.getElementById(id);
  const motion = matchMedia("(prefers-reduced-motion: reduce)");
  let reduce = motion.matches;
  const DPR = Math.min(2, devicePixelRatio || 1);

  function make(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }
  // Splits text into per-letter spans for the rising-letters animation. The parent carries the label.
  function splitInto(row, text, start = 0) {
    row.textContent = ""; let d = start;
    text.split(" ").forEach((w, wi, arr) => {
      const word = make("span", "word"); word.setAttribute("aria-hidden", "true");
      for (const c of w) { const s = make("span", "ch", c); s.style.setProperty("--d", d++); word.appendChild(s); }
      row.appendChild(word); if (wi < arr.length - 1) row.appendChild(document.createTextNode(" "));
    });
    row.classList.add("split");
    return d;
  }
  let dd = 0; document.querySelectorAll("[data-split]").forEach(r => dd = splitInto(r, r.textContent, dd));

  function rng(a) { return () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
  function hash(text) { let h = 2166136261; for (const c of text) { h ^= c.codePointAt(0); h = Math.imul(h, 16777619); } return h >>> 0; }

  /* =========================================================
     HERO: café interior, scene follows the time of day
     ========================================================= */
  const PAL = {
    morning:   { sky: ["#8fc3e8", "#cfe3ee", "#ffe2c4"], wall: "#5a3b33", sun: "#fff4d6", sunY: .62, sunX: .7, glow: "rgba(255,236,200,.5)", lamp: 0, shaft: .22, stars: 0, city: "#7d8fa8", cityLit: 0, tint: "rgba(255,220,180,0)" },
    afternoon: { sky: ["#5fa6dd", "#a9d0ea", "#f6dcb4"], wall: "#5e3c30", sun: "#fffbe8", sunY: .3, sunX: .58, glow: "rgba(255,245,215,.45)", lamp: .15, shaft: .28, stars: 0, city: "#6c7f98", cityLit: 0, tint: "rgba(255,200,140,0)" },
    evening:   { sky: ["#3b1f52", "#c4607c", "#f6a578"], wall: "#3e2430", sun: "#ffd9b8", sunY: .74, sunX: .35, glow: "rgba(255,170,150,.5)", lamp: .8, shaft: .1, stars: .4, city: "#3a2442", cityLit: .6, tint: "rgba(255,140,90,.06)" },
    night:     { sky: ["#0d0b24", "#1f1a45", "#3a2a5c"], wall: "#2a1820", sun: "#f3eedf", sunY: .24, sunX: .72, glow: "rgba(200,210,255,.28)", lamp: 1, shaft: 0, stars: 1, city: "#1d1630", cityLit: 1, tint: "rgba(255,150,80,.08)" }
  };
  function hex2rgb(h) { const n = parseInt(h.slice(1), 16); return [n >> 16, n >> 8 & 255, n & 255]; }
  function toRgb(c) { return c[0] === "#" ? hex2rgb(c) : c.match(/\d+/g).slice(0, 3).map(Number); }
  function lerpC(a, b, t) { const A = toRgb(a), B = toRgb(b); return `rgb(${A.map((v, i) => Math.round(v + (B[i] - v) * t)).join(",")})`; }
  function blend(a, b, t) {
    const o = {};
    for (const k in a) {
      if (Array.isArray(a[k])) o[k] = a[k].map((c, i) => lerpC(c, b[k][i], t));
      else if (typeof a[k] === "number") o[k] = a[k] + (b[k] - a[k]) * t;
      else if (k === "glow" || k === "tint") o[k] = t < .5 ? a[k] : b[k];
      else o[k] = lerpC(a[k], b[k], t);
    }
    return o;
  }
  const hour = new Date().getHours();
  let tod = hour >= 6 && hour < 11 ? "morning" : hour >= 11 && hour < 16 ? "afternoon" : hour >= 16 && hour < 19 ? "evening" : "night";
  let palFrom = PAL[tod], palTo = PAL[tod], palT = 1, pal = PAL[tod];
  const clockBtns = [...document.querySelectorAll(".clock button")];
  function setTod(t) {
    palFrom = pal; palTo = PAL[t]; palT = reduce ? 1 : 0; tod = t;
    clockBtns.forEach(b => b.setAttribute("aria-pressed", b.dataset.t === t ? "true" : "false"));
    if (reduce) { pal = PAL[t]; drawHero(4000); }
  }
  clockBtns.forEach(b => b.addEventListener("click", () => setTod(b.dataset.t)));
  clockBtns.forEach(b => b.setAttribute("aria-pressed", b.dataset.t === tod ? "true" : "false"));

  const cv = $("scene"), ctx = cv.getContext("2d"), hero = document.querySelector(".hero");
  let W = 0, H = 0, U = 1, mx = 0, my = 0, tmx = 0, tmy = 0, hs = 0;
  let stars = [], city = [], pieces = [], steam = [], motes = [];
  const PIECE_COLORS = ["#e0486f", "#3e7fd0", "#f2b45a", "#5fb46a", "#9b6be0", "#f4ece6", "#e8743b"];
  const KINDS = ["die", "meeple", "hex", "card", "gem", "cube", "meeple", "die", "token"];
  function heroSetup() {
    W = hero.clientWidth; H = hero.clientHeight; U = Math.max(.6, Math.min(W / 1200, H / 780));
    cv.width = W * DPR; cv.height = H * DPR; ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    const r = rng(4);
    stars = Array.from({ length: 90 }, () => ({ x: r(), y: r() * .5, s: r() * 1.3 + .3, p: r() * 6.28 }));
    city = []; let x = 0; while (x < 1) { const w = .025 + r() * .05; city.push({ x, w, h: .06 + r() * .14, lit: Array.from({ length: 12 }, () => r() < .45) }); x += w + .004; }
    const n = Math.round(22 * Math.max(.55, W / 1300));
    pieces = Array.from({ length: n }, () => newPiece(r, true));
    motes = Array.from({ length: 60 }, () => ({ x: r() * W, y: r() * H, v: .1 + r() * .25, s: .6 + r() * 1.6, p: r() * 6.28 }));
    steam = [];
  }
  function newPiece(r = Math.random, any = false) {
    const z = .45 + r() * .9;
    return { kind: KINDS[Math.floor(r() * KINDS.length)], c: PIECE_COLORS[Math.floor(r() * PIECE_COLORS.length)], x: r() * W, y: any ? r() * H * .9 : H + 60, z,
      s: (14 + r() * 14) * U * z, rot: r() * 6.28, vr: (r() - .5) * .01, vy: -(.12 + r() * .22) * z, sway: r() * 6.28, pip: 1 + Math.floor(r() * 6) };
  }
  function rr(c, x, y, w, h, r) { c.beginPath(); c.moveTo(x + r, y); c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r); c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath(); }
  function shade(color, f) { const [r, g, b] = toRgb(color); return `rgb(${Math.min(255, r * f) | 0},${Math.min(255, g * f) | 0},${Math.min(255, b * f) | 0})`; }
  const PIPS = { 1: [[0, 0]], 2: [[-.5, -.5], [.5, .5]], 3: [[-.5, -.5], [0, 0], [.5, .5]], 4: [[-.5, -.5], [.5, -.5], [-.5, .5], [.5, .5]], 5: [[-.5, -.5], [.5, -.5], [0, 0], [-.5, .5], [.5, .5]], 6: [[-.5, -.55], [.5, -.55], [-.5, 0], [.5, 0], [-.5, .55], [.5, .55]] };
  function drawPiece(c, p) {
    const s = p.s; c.fillStyle = p.c;
    switch (p.kind) {
      case "die": {
        rr(c, -s, -s, s * 2, s * 2, s * .35); c.fillStyle = "#f7efe6"; c.fill();
        c.fillStyle = "#2a1820";
        for (const [a, b] of PIPS[p.pip]) { c.beginPath(); c.arc(a * s, b * s, s * .17, 0, 7); c.fill(); }
        break; }
      case "meeple": {
        const k = s / 16; c.beginPath();
        c.moveTo(0, -18 * k); c.bezierCurveTo(6 * k, -18 * k, 8 * k, -13 * k, 5 * k, -8 * k); c.lineTo(16 * k, -4 * k); c.quadraticCurveTo(18 * k, 2 * k, 12 * k, 2 * k);
        c.lineTo(7 * k, 2 * k); c.lineTo(13 * k, 16 * k); c.lineTo(3 * k, 16 * k); c.lineTo(0, 9 * k); c.lineTo(-3 * k, 16 * k); c.lineTo(-13 * k, 16 * k); c.lineTo(-7 * k, 2 * k);
        c.lineTo(-12 * k, 2 * k); c.quadraticCurveTo(-18 * k, 2 * k, -16 * k, -4 * k); c.lineTo(-5 * k, -8 * k); c.bezierCurveTo(-8 * k, -13 * k, -6 * k, -18 * k, 0, -18 * k); c.closePath();
        c.fill(); c.fillStyle = "rgba(255,255,255,.18)"; c.beginPath(); c.arc(-2 * k, -13 * k, 3 * k, 0, 7); c.fill();
        break; }
      case "hex": {
        c.beginPath(); for (let i = 0; i < 6; i++) { const a = Math.PI / 3 * i + Math.PI / 6; c.lineTo(Math.cos(a) * s * 1.2, Math.sin(a) * s * 1.2); } c.closePath(); c.fill();
        c.strokeStyle = "rgba(255,255,255,.35)"; c.lineWidth = s * .1; c.stroke();
        c.fillStyle = "rgba(0,0,0,.18)"; c.beginPath(); c.arc(0, 0, s * .45, 0, 7); c.fill();
        break; }
      case "card": {
        rr(c, -s * .75, -s * 1.1, s * 1.5, s * 2.2, s * .18); c.fill();
        c.strokeStyle = "rgba(255,255,255,.75)"; c.lineWidth = s * .08; rr(c, -s * .6, -s * .95, s * 1.2, s * 1.9, s * .12); c.stroke();
        break; }
      case "gem": {
        c.beginPath(); c.moveTo(0, -s); c.lineTo(s * .9, -s * .3); c.lineTo(0, s * 1.1); c.lineTo(-s * .9, -s * .3); c.closePath(); c.fill();
        c.fillStyle = "rgba(255,255,255,.35)"; c.beginPath(); c.moveTo(0, -s); c.lineTo(s * .9, -s * .3); c.lineTo(-s * .9, -s * .3); c.closePath(); c.fill();
        break; }
      case "cube": {
        const a = s * .9; c.fillStyle = shade(p.c, 1.25); c.beginPath(); c.moveTo(0, -a); c.lineTo(a, -a * .5); c.lineTo(0, 0); c.lineTo(-a, -a * .5); c.closePath(); c.fill();
        c.fillStyle = p.c; c.beginPath(); c.moveTo(-a, -a * .5); c.lineTo(0, 0); c.lineTo(0, a); c.lineTo(-a, a * .5); c.closePath(); c.fill();
        c.fillStyle = shade(p.c, .7); c.beginPath(); c.moveTo(a, -a * .5); c.lineTo(0, 0); c.lineTo(0, a); c.lineTo(a, a * .5); c.closePath(); c.fill();
        break; }
      default: {
        c.beginPath(); c.arc(0, 0, s, 0, 7); c.fill(); c.strokeStyle = "rgba(255,255,255,.5)"; c.lineWidth = s * .12; c.beginPath(); c.arc(0, 0, s * .7, 0, 7); c.stroke();
      }
    }
  }

  function drawHero(t) {
    if (palT < 1) { palT = Math.min(1, palT + .02); pal = palT >= 1 ? palTo : blend(palFrom, palTo, 1 - Math.pow(1 - palT, 3)); }
    mx += (tmx - mx) * .05; my += (tmy - my) * .05;
    const p = pal;
    // interior wall
    ctx.fillStyle = p.wall; ctx.fillRect(0, 0, W, H);
    // window geometry
    const ww = Math.min(W * .7, 980 * U + 200), wx = (W - ww) / 2, wy = H * .07, wh = H * .74, arch = ww / 2;
    ctx.save();
    ctx.beginPath(); ctx.moveTo(wx, wy + wh); ctx.lineTo(wx, wy + arch * .55); ctx.ellipse(W / 2, wy + arch * .55, arch, arch * .55, 0, Math.PI, 0); ctx.lineTo(wx + ww, wy + wh); ctx.closePath(); ctx.clip();
    const g = ctx.createLinearGradient(0, wy, 0, wy + wh); g.addColorStop(0, p.sky[0]); g.addColorStop(.6, p.sky[1]); g.addColorStop(1, p.sky[2]);
    ctx.fillStyle = g; ctx.fillRect(wx, wy, ww, wh);
    const ox = -mx * 30, oy = -my * 12 + hs * 60;
    if (p.stars > 0) for (const s of stars) { ctx.globalAlpha = p.stars * (.3 + .7 * Math.abs(Math.sin(t * .0012 + s.p))); ctx.fillStyle = "#fff"; ctx.fillRect(wx + s.x * ww + ox * .3, wy + s.y * wh, s.s, s.s); }
    ctx.globalAlpha = 1;
    const sx = wx + ww * p.sunX + ox * .5, sy = wy + wh * p.sunY + oy * .5, sr = 46 * U;
    const sg = ctx.createRadialGradient(sx, sy, sr * .5, sx, sy, sr * 5); sg.addColorStop(0, p.glow); sg.addColorStop(1, "rgba(255,255,255,0)");
    ctx.fillStyle = sg; ctx.fillRect(wx, wy, ww, wh);
    ctx.fillStyle = p.sun; ctx.beginPath(); ctx.arc(sx, sy, sr, 0, 7); ctx.fill();
    // clouds
    ctx.fillStyle = `rgba(255,255,255,${.35 - p.stars * .3})`;
    for (let i = 0; i < 4; i++) { const cx = wx + ((i * .29 + t * .000008 * (i + 1)) % 1.2 - .1) * ww + ox * .4, cy = wy + wh * (.18 + i * .09); ctx.beginPath(); ctx.ellipse(cx, cy, 70 * U, 12 * U, 0, 0, 7); ctx.ellipse(cx + 40 * U, cy - 8 * U, 40 * U, 12 * U, 0, 0, 7); ctx.fill(); }
    // city
    const base = wy + wh * .86 + oy;
    for (const b of city) {
      const bx = wx + b.x * ww + ox, bw = b.w * ww, bh = b.h * wh;
      ctx.fillStyle = p.city; ctx.fillRect(bx, base - bh, bw, bh + wh);
      if (p.cityLit > 0) { ctx.fillStyle = `rgba(255,205,120,${p.cityLit * .9})`; b.lit.forEach((on, i) => { if (on) ctx.fillRect(bx + 4 + (i % 3) * (bw - 8) / 3, base - bh + 8 + Math.floor(i / 3) * 14 * U, 4 * U, 5 * U); }); }
    }
    ctx.fillStyle = shade(p.wall, .8); ctx.fillRect(wx, base + 30 * U, ww, wh);
    ctx.restore();
    // window frame + mullions
    ctx.strokeStyle = shade(p.wall, .55); ctx.lineWidth = 14 * U;
    ctx.beginPath(); ctx.moveTo(wx, wy + wh); ctx.lineTo(wx, wy + arch * .55); ctx.ellipse(W / 2, wy + arch * .55, arch, arch * .55, 0, Math.PI, 0); ctx.lineTo(wx + ww, wy + wh); ctx.stroke();
    ctx.lineWidth = 6 * U; ctx.beginPath();
    ctx.moveTo(W / 2, wy); ctx.lineTo(W / 2, wy + wh);
    ctx.moveTo(wx + ww * .25, wy + arch * .14); ctx.lineTo(wx + ww * .25, wy + wh); ctx.moveTo(wx + ww * .75, wy + arch * .14); ctx.lineTo(wx + ww * .75, wy + wh);
    ctx.moveTo(wx, wy + wh * .52); ctx.lineTo(wx + ww, wy + wh * .52); ctx.stroke();
    // light shaft onto table
    if (p.shaft > 0.01) {
      ctx.save(); ctx.globalCompositeOperation = "screen";
      const lg = ctx.createLinearGradient(0, wy, 0, H); lg.addColorStop(0, `rgba(255,236,200,${p.shaft})`); lg.addColorStop(1, "rgba(255,236,200,0)");
      ctx.fillStyle = lg; ctx.beginPath(); ctx.moveTo(wx + ww * .2, wy + wh * .3); ctx.lineTo(wx + ww * .8, wy + wh * .3); ctx.lineTo(wx + ww * 1.05, H); ctx.lineTo(wx - ww * .05, H); ctx.closePath(); ctx.fill();
      ctx.restore();
    }
    // plants at the sides
    drawPlant(W * .06 - mx * 50, H * .9, 1.1 * U, t, p); drawPlant(W * .95 - mx * 50, H * .92, 1.3 * U, t + 900, p);
    // table
    const ty = H * .84 + hs * 40;
    const tg = ctx.createLinearGradient(0, ty, 0, H); tg.addColorStop(0, "#6e4129"); tg.addColorStop(.08, "#4c2b1b"); tg.addColorStop(1, "#22120c");
    ctx.fillStyle = tg; ctx.fillRect(0, ty, W, H - ty);
    ctx.fillStyle = "rgba(255,220,180,.25)"; ctx.fillRect(0, ty, W, 2 * U);
    // cup + steam
    const cx = W * .78 - mx * 40, cy = ty + 6 * U;
    ctx.fillStyle = "#efe4da"; rr(ctx, cx - 26 * U, cy - 36 * U, 52 * U, 40 * U, 8 * U); ctx.fill();
    ctx.strokeStyle = "#efe4da"; ctx.lineWidth = 6 * U; ctx.beginPath(); ctx.arc(cx + 30 * U, cy - 18 * U, 11 * U, -1.3, 1.3); ctx.stroke();
    ctx.fillStyle = "#d8c7b9"; ctx.beginPath(); ctx.ellipse(cx, cy + 4 * U, 42 * U, 7 * U, 0, 0, 7); ctx.fill();
    if (!reduce && Math.random() < .25) steam.push({ x: cx + (Math.random() - .5) * 20 * U, y: cy - 38 * U, a: .45, r: 5 * U, ph: Math.random() * 6 });
    for (let i = steam.length - 1; i >= 0; i--) { const s = steam[i]; s.y -= .55; s.r += .12 * U; s.a -= .004; if (s.a <= 0) { steam.splice(i, 1); continue; } ctx.fillStyle = `rgba(255,245,235,${s.a * .45})`; ctx.beginPath(); ctx.arc(s.x + Math.sin(s.y * .04 + s.ph) * 6 * U, s.y, s.r, 0, 7); ctx.fill(); }
    // pieces resting on table
    ctx.save(); ctx.translate(W * .2 - mx * 40, ty - 2 * U); drawPiece(ctx, { kind: "meeple", c: "#e0486f", s: 26 * U }); ctx.translate(46 * U, 4 * U); drawPiece(ctx, { kind: "die", c: "#fff", s: 15 * U, pip: 5 }); ctx.restore();
    // hanging lamps
    for (let i = 0; i < 3; i++) {
      const lx = W * (.22 + i * .28) - mx * 60, sw = reduce ? 0 : Math.sin(t * .0007 + i * 1.7) * .03, len = H * (.16 + (i % 2) * .07);
      ctx.save(); ctx.translate(lx, -10); ctx.rotate(sw);
      ctx.strokeStyle = "#120a0e"; ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(0, len); ctx.stroke();
      if (p.lamp > .02) {
        const cg = ctx.createLinearGradient(0, len, 0, len + H * .7); cg.addColorStop(0, `rgba(255,200,120,${.2 * p.lamp})`); cg.addColorStop(1, "rgba(255,200,120,0)");
        ctx.fillStyle = cg; ctx.beginPath(); ctx.moveTo(-26 * U, len + 20 * U); ctx.lineTo(26 * U, len + 20 * U); ctx.lineTo(170 * U, len + H * .7); ctx.lineTo(-170 * U, len + H * .7); ctx.closePath(); ctx.fill();
        const bg = ctx.createRadialGradient(0, len + 24 * U, 2, 0, len + 24 * U, 90 * U); bg.addColorStop(0, `rgba(255,214,140,${.85 * p.lamp})`); bg.addColorStop(1, "rgba(255,180,100,0)");
        ctx.fillStyle = bg; ctx.fillRect(-90 * U, len - 66 * U, 180 * U, 180 * U);
      }
      ctx.fillStyle = "#1b0f14"; ctx.beginPath(); ctx.moveTo(-8 * U, len); ctx.lineTo(8 * U, len); ctx.lineTo(30 * U, len + 24 * U); ctx.lineTo(-30 * U, len + 24 * U); ctx.closePath(); ctx.fill();
      ctx.fillStyle = p.lamp > .1 ? `rgba(255,228,170,${.4 + .6 * p.lamp})` : "#6a5a50"; ctx.beginPath(); ctx.ellipse(0, len + 24 * U, 12 * U, 5 * U, 0, 0, 7); ctx.fill();
      ctx.restore();
    }
    // floating pieces
    for (const pc of pieces) {
      if (!reduce) { pc.y += pc.vy; pc.rot += pc.vr; }
      if (pc.y < -60) Object.assign(pc, newPiece());
      const px = pc.x + Math.sin(t * .0006 + pc.sway) * 18 * pc.z - mx * 90 * pc.z, py = pc.y - my * 30 * pc.z - hs * 200 * pc.z;
      ctx.save(); ctx.globalAlpha = Math.min(1, .45 + pc.z * .45); ctx.translate(px, py); ctx.rotate(pc.rot);
      ctx.shadowColor = "rgba(0,0,0,.35)"; ctx.shadowBlur = 14 * pc.z; ctx.shadowOffsetY = 6 * pc.z;
      drawPiece(ctx, pc); ctx.restore();
    }
    // dust motes in the light
    for (const m of motes) { if (!reduce) { m.y -= m.v; if (m.y < 0) m.y = H; } ctx.fillStyle = `rgba(255,236,200,${(.15 + .35 * Math.abs(Math.sin(t * .001 + m.p))) * (p.shaft * 2 + p.lamp * .5)})`; ctx.fillRect(m.x + Math.sin(t * .0005 + m.p) * 10, m.y, m.s, m.s); }
    ctx.fillStyle = p.tint; ctx.fillRect(0, 0, W, H);
    const bf = ctx.createLinearGradient(0, H * .82, 0, H); bf.addColorStop(0, "rgba(20,13,28,0)"); bf.addColorStop(1, "rgba(20,13,28,1)");
    ctx.fillStyle = bf; ctx.fillRect(0, H * .82, W, H * .18);
  }
  function drawPlant(x, y, k, t, p) {
    ctx.save(); ctx.translate(x, y);
    ctx.fillStyle = "#2a1a14"; rr(ctx, -34 * k, -10 * k, 68 * k, 70 * k, 6 * k); ctx.fill();
    for (let i = 0; i < 9; i++) {
      const a = -Math.PI / 2 + (i - 4) * .3 + (reduce ? 0 : Math.sin(t * .0008 + i) * .03), L = (90 + (i % 3) * 30) * k;
      ctx.save(); ctx.rotate(a + Math.PI / 2); ctx.fillStyle = i % 2 ? shade(p.wall, .55) : "#1f2a1e";
      ctx.beginPath(); ctx.ellipse(0, -L / 2, 13 * k, L / 2, 0, 0, 7); ctx.fill(); ctx.restore();
    }
    ctx.restore();
  }
  heroSetup(); drawHero(4000);
  // The loop only runs while the hero is on screen (and browsers stop it in background tabs).
  let heroOn = true, hraf = 0;
  function hloop(t) { drawHero(t); hraf = heroOn && !reduce ? requestAnimationFrame(hloop) : 0; }
  function heroStart() { cancelAnimationFrame(hraf); hraf = heroOn && !reduce ? requestAnimationFrame(hloop) : 0; }
  heroStart();
  new IntersectionObserver(([e]) => { heroOn = e.isIntersecting; heroStart(); }).observe(hero);
  hero.addEventListener("pointermove", e => { if (reduce) return; const r = hero.getBoundingClientRect(); tmx = (e.clientX - r.left) / r.width - .5; tmy = (e.clientY - r.top) / r.height - .5; });
  hero.addEventListener("pointerleave", () => { tmx = tmy = 0; });

  /* =========================================================
     SHELF: boxes built from the server-rendered game list
     ========================================================= */
  // The catalog stores no box art colours or sizes, so each game gets a stable pair from its name.
  const BOX_COLORS = [["#b8323f", "#ffe0a3"], ["#2c3e73", "#ffffff"], ["#1f5f52", "#f2d27a"], ["#c96a1f", "#fff1d0"], ["#8a2f5c", "#ffd9e6"], ["#6d9a7b", "#fff6e2"], ["#a87a12", "#fff6d6"], ["#4c7436", "#f3dc9c"], ["#7a3fa0", "#f6dcff"]];
  const inputs = [...document.querySelectorAll('#game-list input[name="gameId"]')];
  let lastColor = -1;
  const GAMES = inputs.map(input => {
    const d = input.dataset, h = hash(d.name);
    let color = h % BOX_COLORS.length; if (color === lastColor) color = (color + 1) % BOX_COLORS.length; lastColor = color;
    const w = 25 + h % 6, min = Number(d.min), max = Number(d.max);
    return { id: input.value, input, name: d.name, min, max, mins: Number(d.duration), category: d.category || "", blurb: d.description || "", cover: d.cover || "", initials: d.initials || d.name.slice(0, 2),
      players: min === max ? `${min}` : `${min}–${max}`, w, h: w - (h >>> 4) % 3, d: 6 + (h >>> 8) % 3, c1: BOX_COLORS[color][0], c2: BOX_COLORS[color][1] };
  });
  const shelfEl = $("shelf-boxes"), shelfSec = $("shelf"), shelfScroll = $("shelf-scroll");
  const players = $("players"), nameRow = document.querySelector("#selected-name .row");
  let slots = [], current = -1;
  const chat = { setGame: () => {} };

  function buildSlot(g, i) {
    const b = make("button", "slot"); b.type = "button";
    b.setAttribute("aria-label", `${g.name}, ${g.players} players, about ${g.mins} minutes`);
    b.setAttribute("aria-pressed", "false");
    for (const [k, v] of Object.entries({ "--w": g.w, "--h": g.h, "--d": g.d, "--c1": g.c1, "--c2": g.c2, "--sf": Math.min(2.6, (g.h - 3.5) / (Math.max(6, g.name.length) * .62)).toFixed(2) })) b.style.setProperty(k, v);
    const box = make("div", "b3"), front = make("div", "face f-front"), cover = make("div", "cover"), spine = make("div", "face f-l");
    cover.append(make("span", "ini", g.initials), make("b", "", g.name));
    if (g.cover) {
      // Real cover on top of the drawn one; if it fails to load the drawn cover simply stays.
      const img = new Image(); img.alt = ""; img.decoding = "async"; img.referrerPolicy = "no-referrer";
      img.addEventListener("load", () => img.classList.add("ok"));
      img.addEventListener("error", () => img.remove());
      img.src = g.cover; cover.appendChild(img);
    }
    cover.appendChild(make("i"));
    front.appendChild(cover); spine.appendChild(make("span", "sp", g.name));
    box.append(front, make("div", "face f-back"), spine, make("div", "face f-r"), make("div", "face f-t"));
    b.append(make("div", "shadow"), box);
    b.addEventListener("click", () => pick(i, true));
    b.addEventListener("keydown", e => {
      if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
      const step = e.key === "ArrowLeft" ? -1 : 1;
      for (let j = i + step; j >= 0 && j < slots.length; j += step) if (!slots[j].classList.contains("dim")) { e.preventDefault(); slots[j].focus(); break; }
    });
    return b;
  }
  function replay(node) { node.classList.remove("swap"); void node.offsetWidth; node.classList.add("swap"); }
  function pick(i, user) {
    if (i === current) return; current = i;
    const g = GAMES[i];
    slots.forEach((s, j) => { s.classList.toggle("out", j === i); s.setAttribute("aria-pressed", j === i ? "true" : "false"); });
    g.input.checked = true;
    shelfSec.style.setProperty("--acc", g.c1); shelfSec.style.setProperty("--acc2", g.c2);
    $("selected-name").setAttribute("aria-label", g.name); splitInto(nameRow, g.name);
    const meta = $("selected-meta"), blurb = $("selected-description");
    meta.replaceChildren(make("span", "on", `${g.players} players`), make("span", "", `About ${g.mins} min`));
    if (g.category) meta.appendChild(make("span", "", g.category));
    blurb.textContent = g.blurb;
    $("ask-link").textContent = `Ask about ${g.name}`;
    if (user) { replay(meta); replay(blurb); }
    players.min = g.min; players.max = g.max;
    const value = Number(players.value);
    if (!value || value < g.min) players.value = g.min; else if (value > g.max) players.value = g.max;
    chat.setGame(g);
    if (user) { const r = slots[i].getBoundingClientRect(); shelfScroll.scrollBy({ left: r.left + r.width / 2 - innerWidth / 2, behavior: reduce ? "auto" : "smooth" }); }
  }
  if (GAMES.length && shelfEl) {
    slots = GAMES.map(buildSlot); shelfEl.append(...slots);
    $("game-list").hidden = true; shelfScroll.hidden = false; $("filters").hidden = false;
    $("selected-meta").classList.add("s2"); $("selected-description").classList.add("s3");
    const FILTERS = { all: () => true, two: g => g.min <= 2 && g.max >= 2, five: g => g.max >= 5, quick: g => g.mins <= 30 };
    const chips = [...document.querySelectorAll("#filters button")];
    chips.forEach(b => b.addEventListener("click", () => {
      chips.forEach(x => x.setAttribute("aria-pressed", x === b ? "true" : "false"));
      const f = FILTERS[b.dataset.f], first = GAMES.findIndex(f);
      GAMES.forEach((g, i) => { slots[i].classList.toggle("dim", !f(g)); });
      $("no-match").hidden = first >= 0;
      if (first >= 0 && !f(GAMES[current])) pick(first, true);
    }));
  }

  /* =========================================================
     TRACK (scroll-driven meeple)
     ========================================================= */
  const path = $("trackPath"), done = $("trackDone"), meeple = $("meeple"), trackSec = $("how");
  const L = path.getTotalLength(), spaces = $("spaces"), stepEls = [...document.querySelectorAll(".step")];
  const STOPS = [.04, .35, .65, .96];
  for (let i = 0; i <= 24; i++) {
    const pt = path.getPointAtLength(L * i / 24), stop = STOPS.some(s => Math.abs(s - i / 24) < .021);
    const c = document.createElementNS("http://www.w3.org/2000/svg", "circle");
    c.setAttribute("cx", pt.x); c.setAttribute("cy", pt.y); c.setAttribute("r", stop ? 12 : 5);
    c.setAttribute("fill", stop ? "#2a1820" : "rgba(244,236,230,.35)"); if (stop) { c.setAttribute("stroke", "#f2b45a"); c.setAttribute("stroke-width", 3); }
    spaces.appendChild(c);
  }
  done.style.strokeDasharray = `${L}`;
  let trackP = 0, trackTarget = 0, trackRaf = 0;
  function placeMeeple(p) {
    const pt = path.getPointAtLength(L * p), ahead = path.getPointAtLength(Math.min(L, L * p + 2));
    const hop = reduce ? 0 : Math.abs(Math.sin(p * Math.PI * 24)) * 10;
    const tilt = Math.atan2(ahead.y - pt.y, ahead.x - pt.x) * 180 / Math.PI * .25;
    meeple.setAttribute("transform", `translate(${pt.x} ${pt.y - 16 - hop}) rotate(${tilt})`);
    done.style.strokeDashoffset = `${L * (1 - p)}`;
    stepEls.forEach((s, i) => s.classList.toggle("on", p >= STOPS[i] - .02));
  }
  placeMeeple(0);
  function tickTrack() { trackP += (trackTarget - trackP) * (reduce ? 1 : .12); placeMeeple(trackP); trackRaf = Math.abs(trackTarget - trackP) > .001 ? requestAnimationFrame(tickTrack) : 0; }

  /* =========================================================
     CHAT: real rule questions about the game pulled from the shelf
     ========================================================= */
  const msgs = $("msgs");
  if (msgs && GAMES.length) {
    const form = $("ask-form"), input = $("ask-input"), send = $("ask-send"), starters = $("starters");
    const STARTERS = ["How do I win?", "What can I do on my turn?", "How does the game end?"];
    const CONFIDENCE = { HIGH: ["High evidence", "high"], MEDIUM: ["Medium evidence", "medium"], LOW: ["Not verified by the rules", "low"] };
    const UNAVAILABLE = "The host can't answer rule questions right now. You can still start the game: lessons and setup work without it.";
    let game = null, request = null, seen = false, greetTimer = 0, showGreeting = null;

    function scrollDown() { msgs.scrollTop = msgs.scrollHeight; }
    function add(kind, text) { const m = make("div", "msg " + kind); m.appendChild(make("p", "", text)); msgs.appendChild(m); scrollDown(); return m; }
    function typing() { const t = make("div", "typing"); t.setAttribute("aria-label", "The host is answering"); t.append(make("i"), make("i"), make("i")); msgs.appendChild(t); scrollDown(); return t; }
    function setBusy(on) { send.disabled = on; starters.querySelectorAll("button").forEach(b => { b.disabled = on; }); }
    function greet() {
      msgs.replaceChildren();
      const text = `Ask me a rule question about ${game.name}. I answer from its rules and show the rule I used.`;
      clearTimeout(greetTimer); showGreeting = null;
      if (reduce || !seen) { add("a", text); return; }
      const dots = typing();
      showGreeting = () => { clearTimeout(greetTimer); showGreeting = null; dots.remove(); add("a", text); };
      greetTimer = setTimeout(showGreeting, 700);
    }
    function addAnswer(data) {
      const m = add("a", data.answer || UNAVAILABLE);
      const [label, tone] = CONFIDENCE[data.confidence] || CONFIDENCE.LOW;
      m.appendChild(make("span", "conf " + tone, label));
      const sources = (data.sources || []).slice(0, 3);
      if (sources.length) {
        const details = make("details"); details.appendChild(make("summary", "", sources.length === 1 ? "View source" : "View sources"));
        for (const s of sources) {
          const item = make("div", "src");
          item.append(make("strong", "", s.document || "Rules"), make("span", "", [s.section, s.page != null ? "Page " + s.page : ""].filter(Boolean).join(" · ")));
          if (s.quote) item.appendChild(make("blockquote", "", s.quote));
          details.appendChild(item);
        }
        m.appendChild(details);
      }
      scrollDown();
    }
    async function ask(question) {
      if (!game || !question || send.disabled) return;
      showGreeting?.(); // a question asked while the greeting is still "typing" must not jump ahead of it
      add("q", question);
      const dots = typing(), control = request = new AbortController();
      setBusy(true);
      try {
        const response = await fetch(`/api/v1/games/${encodeURIComponent(game.id)}/questions`, {
          method: "POST", headers: { "Content-Type": "application/json", "Accept": "application/json" }, signal: control.signal, body: JSON.stringify({ question })
        });
        const data = await response.json().catch(() => null);
        if (control.signal.aborted) return;
        dots.remove();
        if (response.ok && data) addAnswer(data);
        else add("a err", (response.status === 429 || response.status === 400) && data?.message ? data.message : UNAVAILABLE);
      } catch (error) {
        if (error.name === "AbortError" || control.signal.aborted) return;
        dots.remove(); add("a err", "Couldn't reach the host. Check your connection and try again.");
      } finally { if (request === control) { request = null; setBusy(false); } }
    }
    // Switching games starts a fresh conversation: earlier answers were about another game.
    chat.setGame = next => {
      if (game === next) return;
      request?.abort(); request = null; game = next;
      $("chat-game").textContent = next.name; input.placeholder = `Ask about ${next.name}`;
      setBusy(false); greet();
    };
    STARTERS.forEach(text => { const b = make("button", "", text); b.type = "button"; b.addEventListener("click", () => ask(text)); starters.appendChild(b); });
    form.addEventListener("submit", e => { e.preventDefault(); const q = input.value.trim(); if (!q || send.disabled) return; input.value = ""; ask(q); });
    new IntersectionObserver(([e], io) => { if (e.isIntersecting) { io.disconnect(); if (msgs.children.length === 1 && !request) { seen = true; greet(); } seen = true; } }, { threshold: .5 }).observe(msgs);
  }
  if (GAMES.length && shelfEl) pick(Math.max(0, GAMES.findIndex(g => g.input.checked)));

  /* =========================================================
     DUST in the ask section
     ========================================================= */
  const dc = $("dust"), askSec = $("ask");
  let dsetup = () => {}, ddraw = () => {}, dustStart = () => {};
  if (dc) {
    const dx = dc.getContext("2d");
    let DW = 0, DH = 0, flecks = [], dOn = false, draf = 0;
    dsetup = () => { DW = askSec.clientWidth; DH = askSec.clientHeight; dc.width = DW * DPR; dc.height = DH * DPR; dx.setTransform(DPR, 0, 0, DPR, 0, 0); const r = rng(9); flecks = Array.from({ length: Math.round(DW / 24) }, () => ({ x: r() * DW, y: r() * DH, p: r() * 6.28, s: .4 + r() * .9, r: 1 + r() * 2 })); };
    ddraw = t => {
      dx.clearRect(0, 0, DW, DH);
      for (const f of flecks) {
        const x = f.x + Math.sin(t * .0004 * f.s + f.p) * 40, y = ((f.y - (reduce ? 0 : t * .008 * f.s)) % DH + DH) % DH, a = .2 + .6 * Math.max(0, Math.sin(t * .002 * f.s + f.p));
        const g = dx.createRadialGradient(x, y, 0, x, y, f.r * 6); g.addColorStop(0, `rgba(255,214,150,${a})`); g.addColorStop(1, "rgba(255,200,120,0)");
        dx.fillStyle = g; dx.beginPath(); dx.arc(x, y, f.r * 6, 0, 7); dx.fill();
      }
    };
    const dloop = t => { ddraw(t); draf = dOn && !reduce ? requestAnimationFrame(dloop) : 0; };
    dustStart = () => { cancelAnimationFrame(draf); draf = dOn && !reduce ? requestAnimationFrame(dloop) : 0; };
    dsetup(); ddraw(3000);
    new IntersectionObserver(([e]) => { dOn = e.isIntersecting; dustStart(); }).observe(askSec);
  }

  /* =========================================================
     scroll + reveals + resize + motion preference
     ========================================================= */
  const nav = $("nav");
  function onScroll() {
    hs = reduce ? 0 : Math.min(1, Math.max(0, scrollY / hero.offsetHeight)); hero.style.setProperty("--hs", hs.toFixed(3));
    nav.classList.toggle("solid", scrollY > 40);
    const r = trackSec.getBoundingClientRect();
    trackTarget = Math.min(1, Math.max(0, (innerHeight * .85 - r.top) / (r.height * .9)));
    if (!trackRaf) trackRaf = requestAnimationFrame(tickTrack);
  }
  addEventListener("scroll", onScroll, { passive: true }); onScroll();
  const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } }), { threshold: .15 });
  document.querySelectorAll("[data-reveal]").forEach(el => io.observe(el));
  // Phones fire resize when the address bar slides away; only rebuild when the canvas size really changed.
  let resizeTimer = 0;
  addEventListener("resize", () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      if (hero.clientWidth !== W || hero.clientHeight !== H) { heroSetup(); drawHero(performance.now()); }
      dsetup(); ddraw(performance.now()); onScroll();
    }, 150);
  });
  motion.addEventListener("change", () => {
    reduce = motion.matches; tmx = tmy = 0;
    if (reduce) { palT = 1; pal = palTo; }
    onScroll(); drawHero(4000); ddraw(3000); heroStart(); dustStart();
  });
})();
