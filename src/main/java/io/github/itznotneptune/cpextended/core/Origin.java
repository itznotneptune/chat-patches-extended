// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

/** Where a captured message came from. UNKNOWN when Chat Patches' restored tag can't be read (degraded mode). */
public enum Origin { LIVE, RESTORED, UNKNOWN }
