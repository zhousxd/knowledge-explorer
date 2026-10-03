<script setup lang="ts">
withDefaults(defineProps<{ theme?: string }>(), { theme: 'academy' });
</script>

<template>
  <svg
    class="theme-artwork"
    viewBox="0 0 320 200"
    fill="none"
    aria-hidden="true"
    focusable="false"
  >
    <g v-if="theme === 'academy'">
      <path
        class="art-soft"
        d="M27 99 160 33 293 99 268 106H51Z"
      />
      <path
        class="art-line"
        d="M27 99 160 33 293 99M160 33 76 103M160 33 107 103M160 33 135 103M160 33 185 103M160 33 213 103M160 33 244 103"
      />
      <path
        class="art-solid"
        d="m18 113 40-15 102-45 102 45 40 15-28 13H46Z"
      />
      <path
        class="art-line"
        d="M47 126v49h226v-49M73 127v47M110 127v47M210 127v47M247 127v47M35 180h250M24 187h272"
      />
      <path
        class="art-solid"
        d="M141 137h38v37h-38Z"
      />
      <rect
        class="art-paper"
        x="124"
        y="113"
        width="72"
        height="20"
      />
      <text
        class="art-letters"
        x="160"
        y="127"
        text-anchor="middle"
      >书院</text>
      <path
        class="art-faint"
        d="M160 12v15m0 163v7M10 181h10m280 0h10M44 23h38m-19-8v16M260 35h34m-17-8v16"
      />
    </g>
    <g v-else-if="theme === 'cuisine'">
      <ellipse
        class="art-line"
        cx="161"
        cy="156"
        rx="115"
        ry="21"
      />
      <path
        class="art-soft"
        d="M55 157q17 34 105 34t107-34"
      />
      <path
        class="art-line"
        d="M55 157q17 34 105 34t107-34M89 61c-8 21-7 60 35 86 24 15 43 8 48-1-41-12-54-49-61-67-3-10-14-19-22-18Z"
      />
      <path
        class="art-solid"
        d="M210 62c8 20 10 64-35 87-21 12-45 10-49-2 46-14 49-46 56-66 5-14 19-20 28-19Z"
      />
      <path
        class="art-line"
        d="M93 64c-2-18 3-29 22-38M206 64c-5-20-19-25-36-26M138 73q14-30 36-31-4 28-28 35"
      />
      <path
        class="art-faint"
        d="M69 29h26m-13-8v16M241 105h33m-16-8v16M33 145h10M286 145h10"
      />
    </g>
    <g v-else-if="theme === 'sound'">
      <circle
        class="art-faint"
        cx="160"
        cy="103"
        r="75"
      />
      <circle
        class="art-line"
        cx="160"
        cy="103"
        r="49"
      />
      <path
        class="art-line"
        d="M15 103h28l9-23 14 47 16-69 17 90 17-119 19 146 19-151 18 147 17-124 16 97 17-68 15 47 13-22h39"
      />
      <path
        class="art-faint"
        d="M15 103h290M160 12v182M47 32h32m-16-8v16M261 166h32m-16-8v16"
      />
    </g>
    <g v-else>
      <rect
        class="art-line"
        x="94"
        y="40"
        width="116"
        height="130"
      />
      <path
        class="art-faint"
        d="M116 28h107v127M113 78h72M113 96h72M113 114h45M113 152h72"
      />
    </g>
  </svg>
</template>

<style scoped>
.theme-artwork { color: var(--ke-primary); }
.art-solid { fill: currentcolor; }
.art-soft { fill: color-mix(in srgb, currentcolor 12%, transparent); }
.art-paper { fill: var(--ke-surface); stroke: currentcolor; }
.art-letters { fill: currentcolor; font-family: var(--ke-font-display); font-size: 10px; letter-spacing: 3px; }
.art-line { stroke: currentcolor; stroke-width: 1.4; stroke-linecap: round; stroke-linejoin: round; }
.art-faint { stroke: currentcolor; stroke-width: .7; opacity: .4; }
</style>
