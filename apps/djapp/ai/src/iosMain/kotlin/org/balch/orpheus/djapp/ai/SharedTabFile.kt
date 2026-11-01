package org.balch.orpheus.djapp.ai

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfURL

/** The app group the share extension writes into; ShareViewController.swift names the same group and file. */
private const val SHARE_APP_GROUP = "group.org.balch.djapp.ai"
private const val SHARED_TAB_FILE = "shared-tab.txt"

// Text already offered whose file could not be deleted, so it is not re-offered on every activation.
private var lastOffered: String? = null

/** Reads the text the share extension left, deleting the file so it is offered once. Null when there is none. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun readAndDeleteSharedTab(): String? {
    val fm = NSFileManager.defaultManager
    val dir = fm.containerURLForSecurityApplicationGroupIdentifier(SHARE_APP_GROUP) ?: return null
    val url = dir.URLByAppendingPathComponent(SHARED_TAB_FILE) ?: return null
    val path = url.path ?: return null
    if (!fm.fileExistsAtPath(path)) return null
    val text = NSString.stringWithContentsOfURL(url, NSUTF8StringEncoding, null)
    val deleted = fm.removeItemAtURL(url, null)
    val stale = text != null && text == lastOffered
    // A clean delete clears the memory, so sharing the same tab again later still works.
    lastOffered = if (deleted) null else text
    return if (stale) null else text
}
