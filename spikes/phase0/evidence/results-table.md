| Mode | Test | Result | Details |
|---|---|---|---|
| s1a | V-59.2 | **PASS** | `{"searchFieldBeforeInit": "null", "reflection": "ok"}` |
| s1a | V-59.1 | **PASS** | `{"reflection": "ok"}` |
| s1a | V-01 | **PASS** | `{"liveCaptures": 6, "violations": 0}` |
| s1a | hide-basic | **PASS** | `{"deliver": {"t": "deliver", "frame": 7, "token": "cpx#H1", "hidden": true, "m2_equals_m3": true, "m3Calls": 1, "depth": 1}, "absentFromAllMessages": true}` |
| s1a | V-04 | **PASS** | `{"condense": {"t": "condense", "frame": 9, "token": "cpx#Q3", "k": 1, "sizeAtEntry": 5, "sizeNow": 4, "sizeDelta": true, "snapshotMissing": true, "truthRemoved": 1, "detected": true, "dupeSlotDiagnostic": true, "hidden": false, "wantsHide": true}}` |
| s1a | V-04c.c5 | **PASS** | `{"condense": {"t": "condense", "frame": 14, "token": "cpx#C-c5", "k": 5, "sizeAtEntry": 9, "sizeNow": 8, "sizeDelta": true, "snapshotMissing": true, "truthRemoved": 1, "detected": true, "dupeSlotDiagnostic": true, "hidden": false, "wantsHide": false}, "fallbackEvents": 0}` |
| s1a | V-04c.c0 | **PASS** | `{"condense": {"t": "condense", "frame": 19, "token": "cpx#C-c0", "k": 10, "sizeAtEntry": 13, "sizeNow": 12, "sizeDelta": true, "snapshotMissing": true, "truthRemoved": 1, "detected": true, "dupeSlotDiagnostic": true, "hidden": false, "wantsHide": false}, "fallbackEvents": 0}` |
| s1a | V-04c.c500 | **PASS** | `{"condense": {"t": "condense", "frame": 31, "token": "cpx#C-c500", "k": 24, "sizeAtEntry": 24, "sizeNow": 23, "sizeDelta": true, "snapshotMissing": true, "truthRemoved": 1, "detected": true, "dupeSlotDiagnostic": true, "hidden": false, "wantsHide": false}, "fallbackEvents": 0}` |
| s1a | V-04c.cneg | **PASS** | `{"condense": {"t": "condense", "frame": 34, "token": "cpx#C-cneg", "k": 10, "sizeAtEntry": 26, "sizeNow": 26, "sizeDelta": false, "snapshotMissing": false, "truthRemoved": 0, "detected": false, "dupeSlotDiagnostic": false, "hidden": false, "wantsHide": false}, "fallbackEvents": 0}` |
| s1a | V-04c.counteroff | **PASS** | `{"condense": {"t": "condense", "frame": 36, "token": "cpx#C-counteroff", "k": 0, "sizeAtEntry": 28, "sizeNow": 28, "sizeDelta": false, "snapshotMissing": false, "truthRemoved": 0, "detected": false, "dupeSlotDiagnostic": false, "hidden": false, "wantsHide": false}, "fallbackEvents": 0}` |
| s1a | V-04c.foreignadd | **PASS** | `{"condense": {"t": "condense", "frame": 38, "token": "cpx#C-foreignadd", "k": 1, "sizeAtEntry": 31, "sizeNow": 31, "sizeDelta": false, "snapshotMissing": true, "truthRemoved": 1, "detected": true, "dupeSlotDiagnostic": true, "hidden": false, "wantsHide": false}, "fallbackEvents": 0}` |
| s1a | V-04c.fault | **PASS** | `{"condense": {"t": "condense", "frame": 40, "token": "cpx#C-fault", "k": 10, "sizeAtEntry": 33, "sizeNow": 32, "sizeDelta": true, "snapshotMissing": true, "truthRemoved": 1, "detected": true, "dupeSlotDiagnostic": true, "hidden": false, "wantsHide": false}, "fallbackEvents": 2}` |
| s1a | V-47 | **PASS** | `{"outerDeliveries": 1, "innerDeliveries": 1, "allM2EqualsM3": true, "innerDepth": 2}` |
| s1b | V-02 | **PASS** | `{"liveTokensNotExactlyOnce": 0, "framesCancelledWhileLoading": 0}` |
| s1b | V-03 | **PASS** | `{"restoredCaptures": 3042, "s1aChatLogCount": 3042, "liveTaggedRestored": 0, "bulkCapturedAsLive": 0}` |
| s1b | V-27f | **PASS** | `{"compared": ["cpx#S-say1", "cpx#S-sys1", "cpx#S-dupe", "cpx#S-player1"], "diffs": []}` |
| s1b | V-20m | **PASS** | `{"details": {"swapIdentity": true, "linesRebuilt": true, "swapBackIdentity": true, "logUnchangedBySwap": true, "deleteRemovedFromSwappedList": true, "deleteRemovedFromChatLog": true}, "condensedOnSwappedList": true, "searchFindsSwappedEntry": true}` |
| s1b | V-05 | **PASS** | `{"displayQueueCallsWithoutFrame": 6059, "newDeliveries": 0}` |
| s1b | V-20m.boundary | **PASS** | `{"note": "boundary line at top after world switch with swapped list"}` |
| s1b | V-20m.noRelog | **PASS** | `{"swapTokenOccurrencesInChatlog": 0, "chatlogBytes": 835367}` |
| s1c | V-06.f3d.vanillaClearing=false | **PASS** | `{"events": [{"t": "clear", "history": false, "vanillaClearing": false, "sizeBefore": 3064, "predictedCleared": true, "actualEmpty": true, "ok": true}]}` |
| s1c | V-36.classS | **PASS** | `{"deliver": {"t": "deliver", "frame": 3078, "token": "cpx#D1", "hidden": false, "m2_equals_m3": true, "m3Calls": 1, "depth": 1}}` |
| s1c | V-06.disconnect.vanillaClearing=false | **PASS** | `{"events": [{"t": "clear", "history": true, "vanillaClearing": false, "sizeBefore": 6, "predictedCleared": false, "actualEmpty": false, "ok": true}]}` |
| s1c | V-06.f3d.vanillaClearing=true | **PASS** | `{"events": [{"t": "clear", "history": false, "vanillaClearing": true, "sizeBefore": 12, "predictedCleared": true, "actualEmpty": true, "ok": true}]}` |
| s1c | V-06.disconnect.vanillaClearing=true | **PASS** | `{"events": [{"t": "clear", "history": true, "vanillaClearing": false, "sizeBefore": 6, "predictedCleared": false, "actualEmpty": false, "ok": true}, {"t": "clear", "history": true, "vanillaClearing": true, "sizeBefore": 5, "predictedCleared": true, "actualEmpty": true, "ok": true}]}` |
| s1conflict_bool | V-59.4.s1conflict_bool | **PASS** | `{"reflectionStatus": "failed: java.lang.IllegalStateException: ambiguous field 'showSearchBar': exactCount=1 renamedChatPatchesVariant=true", "searchBarShownRead": "null", "searchIntegrationOff": true, "note": "Chat Patches default showSearchBar=true; a 'false' read with status ok means the wrong field was bound"}` |
| s1conflict_int | V-59.4.s1conflict_int | **PASS** | `{"reflectionStatus": "failed: java.lang.IllegalStateException: ambiguous field 'showSearchBar': exactCount=2 renamedChatPatchesVariant=false", "searchBarShownRead": "null", "searchIntegrationOff": true, "note": "Chat Patches default showSearchBar=true; a 'false' read with status ok means the wrong field was bound"}` |
| s2 | V-55+V-39 | **PASS** | `{"framesChecked": 1551, "anomalies": 0}` |
| s2 | V-53.input | **PASS** | `{"rect": [2, 226, 423, 12, 3, 226, 8, 14], "overlapGuiPx": 2640, "A_pureWindowPixelsInElement": 0, "A_elementTextPixelsCovered": 0, "A_elementTextPixels": 0, "B_pureWindowPixelsInElement": 0, "B_elementTextPixelsCovered": 0}` |
| s2 | V-53.main_chat | **PASS** | `{"rect": [0, 21, 325, 180], "overlapGuiPx": 13200, "A_pureWindowPixelsInElement": 0, "A_elementTextPixelsCovered": 0, "A_elementTextPixels": 35400, "B_pureWindowPixelsInElement": 0, "B_elementTextPixelsCovered": 0}` |
| s2 | V-53.cp_search_field | **PASS** | `{"rect": [20, 207, 112, 12], "overlapGuiPx": 1344, "A_pureWindowPixelsInElement": 0, "A_elementTextPixelsCovered": 0, "A_elementTextPixels": 0, "B_pureWindowPixelsInElement": 0, "B_elementTextPixelsCovered": 0}` |
| s2 | V-07 | **PASS** | `{"tooltipDrawn": true, "drawnInSameFrameAsDeferredPass": true}` |
| s2 | V-08 | **PASS** | `{"noTooltipUnderInputBox": true}` |
| s2 | V-57 | **PASS** | `{"leftConsumedVanillaDelta": 0, "rightConsumedVanillaDelta": 0, "noContextMenuAfterRight": true, "middlePassedVanillaDelta": 1, "emptyAreaVanillaDelta": 1, "scrollWindowVanillaDelta": 0, "scrollMainChatVanillaDelta": 1}` |
| s2 | V-09c.focus | **PASS** | `{"before": ["abc", "3", "true"], "after": ["abc", "3", "true"]}` |
| s2 | V-58 | **PASS** | `{"earlyConsumerPreemptsExtended": true, "extendedWorksAfterObserver": true, "extendedPreemptsLateConsumer": true}` |
| s2 | V-54 | **PASS** | `{"samples": 60, "mismatches": 0, "badPoints": []}` |
| s2 | V-09e.capture+cancel | **PASS** | `{"movedDuringDrag": true, "cancelledOnClose": true}` |
| s2 | V-53.command_suggestions | **PASS** | `{"rect": [9, 105, 41, 120], "overlapGuiPx": 2460, "A_pureWindowPixelsInElement": 0, "A_elementTextPixelsCovered": 0, "A_elementTextPixels": 0, "B_pureWindowPixelsInElement": 0, "B_elementTextPixelsCovered": 0}` |
| s2 | V-53.cp_settings_panel | **PASS** | `{"rect": [2, 127, 146, 76], "overlapGuiPx": 5400, "A_pureWindowPixelsInElement": 0, "A_elementTextPixelsCovered": 0, "A_elementTextPixels": 4496, "B_pureWindowPixelsInElement": 0, "B_elementTextPixelsCovered": 0}` |
| s2 | V-53.cp_context_menu | **PASS** | `{"rect": [10, 146, 74, 54], "overlapGuiPx": 3996, "A_pureWindowPixelsInElement": 0, "A_elementTextPixelsCovered": 0, "A_elementTextPixels": 2544, "B_pureWindowPixelsInElement": 0, "B_elementTextPixelsCovered": 0}` |
