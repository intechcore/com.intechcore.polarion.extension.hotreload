package com.intechcore.polarion.extension.hotreload.service;

import java.util.List;

/**
 * Result of a rescan, returned by the REST endpoint as JSON.
 *
 * @param applied         {@code false} for a dry run
 * @param changes         jar differences found, one line each
 * @param log             what was done, in order
 * @param restartRequired contributions that only a restart picks up
 * @param errors          failures; a non-empty list means the server may be in a mixed state
 */
public record RescanReport(boolean applied, List<String> changes, List<String> log, List<String> restartRequired, List<String> errors) {
}
