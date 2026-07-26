// SPDX-License-Identifier: MIT

(function() {
    'use strict';

    /**
     * Restores a stable range emitted by captureVisibleRanges. Offsets are Unicode code points
     * across body text nodes, with ruby annotations and non-content nodes excluded.
     */
    window.hoshiReader.restoreCodePointRange = function(start, endExclusive) {
        if (!Number.isInteger(start) || !Number.isInteger(endExclusive) ||
            start < 0 || endExclusive <= start) {
            return false;
        }

        var walker = this.createWalker();
        var logicalOffset = 0;
        var startBoundary = null;
        var endBoundary = null;
        var node;

        function utf16Offset(codePoints, codePointOffset) {
            var offset = 0;
            for (var i = 0; i < codePointOffset; i++) {
                offset += codePoints[i].length;
            }
            return offset;
        }

        while (node = walker.nextNode()) {
            var parent = node.parentElement;
            var tag = parent && parent.tagName ? parent.tagName.toLowerCase() : '';
            if (tag === 'script' || tag === 'style' || tag === 'template' || tag === 'noscript') continue;
            var codePoints = Array.from(node.textContent || '');
            if (!codePoints.length) continue;
            var nextOffset = logicalOffset + codePoints.length;

            if (!startBoundary && start >= logicalOffset && start <= nextOffset) {
                startBoundary = {
                    node: node,
                    offset: utf16Offset(codePoints, start - logicalOffset)
                };
            }
            if (!endBoundary && endExclusive >= logicalOffset && endExclusive <= nextOffset) {
                endBoundary = {
                    node: node,
                    offset: utf16Offset(codePoints, endExclusive - logicalOffset)
                };
            }

            logicalOffset = nextOffset;
            if (startBoundary && endBoundary) break;
        }

        if (!startBoundary || !endBoundary || endExclusive > logicalOffset) return false;

        var range = document.createRange();
        try {
            range.setStart(startBoundary.node, startBoundary.offset);
            range.setEnd(endBoundary.node, endBoundary.offset);
        } catch (_) {
            return false;
        }

        if (window.CSS && window.CSS.highlights && window.Highlight) {
            window.CSS.highlights.set('hoshi-source-anchor', new window.Highlight(range));
        }

        if (this.continuousMode) {
            var markerRange = range.cloneRange();
            markerRange.collapse(true);
            var marker = document.createElement('span');
            marker.setAttribute('aria-hidden', 'true');
            marker.style.cssText = 'display:inline-block;width:1px;height:1px;pointer-events:none';
            markerRange.insertNode(marker);
            marker.scrollIntoView({
                block: 'start',
                inline: 'nearest',
                behavior: 'instant'
            });
            marker.remove();
        } else {
            var context = this.getScrollContext();
            if (context.pageSize <= 0) return false;
            var rect = this.getRect(range);
            if (!rect) return false;
            var currentScroll = this.getPagePosition(context);
            var anchor = (context.vertical ? rect.top : rect.left) + currentScroll;
            this.setPagePosition(context, this.alignToPage(context, anchor));
        }
        return true;
    };
})();
