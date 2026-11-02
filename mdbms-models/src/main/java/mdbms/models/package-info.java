/**
 * Shared types used across all mDBMS modules, grouped by domain concept.
 *
 * <p>Subpackages may only depend on {@code data}; {@code data} and {@code schema}
 * depend on nothing else in this module. Keep {@code access}, {@code query},
 * {@code transaction} and {@code log} independent of each other.
 */
package mdbms.models;
