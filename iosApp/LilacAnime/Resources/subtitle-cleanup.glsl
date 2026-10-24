//!HOOK MAIN
//!BIND HOOKED
//!SAVE LilacText
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h 2 /
//!DESC Lilac subtitle text
// REGION_CONSTANTS
bool inRect(vec2 p,vec4 r){return p.x>=r.x&&p.x<=r.x+r.z&&p.y>=r.y&&p.y<=r.y+r.w;}
bool inBand(vec2 p){for(int i=0;i<4;i++){if(i>=uRectCount)break;if(inRect(p,uRects[i]))return true;}return false;}
float lum(vec3 c){return dot(c,vec3(0.299,0.587,0.114));}

vec4 hook() {
  vec2 p = HOOKED_pos; if (!inBand(p)) { return  vec4(0.0); }
  float s = max(HOOKED_size.y / 1080.0, 0.5); vec2 pt = 1.0 / HOOKED_size;
  float l = lum(HOOKED_tex( p).rgb); bool bright = l > 0.72, dark = l < 0.24;
  if (!bright && !dark) { return  vec4(0.0); }
  bool nearBright = false, nearDark = false;
  for (int i = 0; i < 8; i++) {
    float a = 0.7853982 * float(i); vec2 d = vec2(cos(a), sin(a)) * pt;
    float l1 = lum(HOOKED_tex( p + d * (3.0 * s)).rgb), l2 = lum(HOOKED_tex( p + d * (6.0 * s)).rgb);
    if (bright) nearDark = nearDark || l1 < 0.24 || l2 < 0.24;
    if (dark) nearBright = nearBright || l1 > 0.72 || l2 > 0.72;
  }
  float m = ((bright && nearDark) || (dark && nearBright)) ? 1.0 : 0.0;
  return  vec4(m, m, m, 1.0);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND LilacText
//!SAVE LilacWide
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h 2 /
//!DESC Lilac subtitle dilate
// REGION_CONSTANTS
bool inRect(vec2 p,vec4 r){return p.x>=r.x&&p.x<=r.x+r.z&&p.y>=r.y&&p.y<=r.y+r.w;}
bool inBand(vec2 p){for(int i=0;i<4;i++){if(i>=uRectCount)break;if(inRect(p,uRects[i]))return true;}return false;}
float lum(vec3 c){return dot(c,vec3(0.299,0.587,0.114));}


vec4 hook() {
  vec2 p = HOOKED_pos; if (!inBand(p)) { return  vec4(0.0); }
  float s = max(HOOKED_size.y / 1080.0, 0.5); vec2 pt = 1.0 / HOOKED_size; float m = LilacText_tex( p).r;
  for (int i = 0; i < 8; i++) { float a = 0.7853982 * float(i); vec2 d = vec2(cos(a), sin(a)) * pt; m = max(m, LilacText_tex( p + d * (2.0 * s)).r); m = max(m, LilacText_tex( p + d * (4.0 * s)).r); }
  return  vec4(m, m, m, 1.0);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND LilacWide
//!DESC Lilac subtitle fill
// REGION_CONSTANTS
bool inRect(vec2 p,vec4 r){return p.x>=r.x&&p.x<=r.x+r.z&&p.y>=r.y&&p.y<=r.y+r.w;}
bool inBand(vec2 p){for(int i=0;i<4;i++){if(i>=uRectCount)break;if(inRect(p,uRects[i]))return true;}return false;}
float lum(vec3 c){return dot(c,vec3(0.299,0.587,0.114));}

 
vec4 hook() {
  vec2 p = HOOKED_pos; vec4 c = HOOKED_tex( p);
  if (!inBand(p) || LilacWide_tex( p).r < 0.5) { return  vec4(c.rgb, 1.0); }
  if (0 == 1) { return  vec4(mix(c.rgb, vec3(0.0, 0.6, 1.0), 0.7), 1.0); }
  float s = max(HOOKED_size.y / 1080.0, 0.5); vec2 pt = 1.0 / HOOKED_size; vec3 acc = vec3(0.0); float cnt = 0.0;
  for (int ring = 0; ring < 7; ring++) {
    float r = (5.0 + 5.0 * float(ring)) * s;
    for (int k = 0; k < 12; k++) {
      float a = 0.5235988 * float(k) + 0.45 * float(ring);
      vec2 q = clamp(p + vec2(cos(a), sin(a)) * r * pt, vec2(0.0), vec2(1.0));
      if (LilacWide_tex( q).r < 0.5) { acc += HOOKED_tex( q).rgb; cnt += 1.0; }
    }
    if (cnt >= 8.0) break;
  }
  return  vec4(cnt < 1.0 ? c.rgb : acc / cnt, 1.0);
}
