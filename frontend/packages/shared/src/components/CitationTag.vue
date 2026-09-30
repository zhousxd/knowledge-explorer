<script setup lang="ts">
defineProps<{ index: number }>();
defineEmits<{ click: [] }>();
</script>

<template>
  <sup
    class="cite"
    role="button"
    :aria-label="`查看出处 ${index}`"
    tabindex="0"
    @click="$emit('click')"
  >[{{ index }}]</sup>
</template>

<style scoped>
.cite { color: var(--ke-primary); font-weight: 700; font-size: 11px; cursor: pointer; }
.cite:hover { text-decoration: underline; }
</style>
