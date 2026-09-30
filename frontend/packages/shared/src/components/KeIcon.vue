<script setup lang="ts">
defineProps<{ name: string }>();
</script>

<template>
  <svg
    class="ke-icon"
    aria-hidden="true"
  ><use :href="`#i-${name}`" /></svg>
</template>

<style>
.ke-icon { width: 20px; height: 20px; stroke: currentcolor; fill: none; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; flex-shrink: 0; }
</style>
