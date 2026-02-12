# Gallery Performance Playbook

## Phase 1 (implemented foundations)

1. **Keyset pagination for media queries**
   - Uses `(DATE_MODIFIED DESC, _ID DESC)` ordering.
   - Uses last item cursor (`dateModified`, `id`) instead of `OFFSET`.
   - Reduces query slowdown for large galleries.

2. **Debounced MediaStore observer refresh**
   - Media refresh is now batched with a 500ms debounce.
   - Avoids repeated full refresh storms during multiple storage updates.

3. **Thumbnail request sizing based on visible grid geometry**
   - Thumbnail decode size is computed from screen width + column count.
   - Prevents over-decoding and lowers decode/memory pressure.

4. **Selection performance improvement**
   - Selected items are resolved through an indexed map (`stableId -> MediaItem`) to avoid full-list filtering per update.

5. **Video update guard**
   - Full-screen `VideoView` only resets source URI when the URI changes.
   - Prevents unnecessary player reset in updates.

## Smooth scrolling playbook (practical)

1. **Measure first**
   - Monitor janky frames with `JankStats` in debug builds.
   - Track query latency logs for `loadNextPage`, `loadNextAlbumPage`, and `loadAlbums`.

2. **Budget targets**
   - Keep P95 page query latency under 120ms.
   - Keep jank ratio under 5% on representative devices.

3. **Iterate by bottleneck**
   - If query latency spikes: reduce page size and optimize `MediaStore` selection predicates.
   - If decode spikes: lower request sizes and validate cache hit rate.
   - If composition spikes: split composables and reduce state invalidation scope.

4. **Regression protection (next step)**
   - Add Macrobenchmark + Baseline Profile module.
   - Add CI threshold checks for startup + frame timing.
