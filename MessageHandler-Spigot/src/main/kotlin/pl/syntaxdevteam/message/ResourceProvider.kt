package pl.syntaxdevteam.message

import java.io.File
import java.io.InputStream

/**
 * Abstraction responsible for accessing configuration values and bundled resources
 * required by [MessageHandler].
 */
interface ResourceProvider {
    /**
     * Główny katalog danych pluginu, w którym przechowywane są pliki językowe.
     */
    val dataFolder: File

    /**
     * Pobiera wartość konfiguracyjną z kropkowanej ścieżki, zwracając [default] gdy klucz nie istnieje.
     */
    fun <T> getConfigValue(path: String, default: T): T

    /**
     * Zapisuje zasób z pakietu JAR do katalogu danych.
     *
     * @param resourcePath ścieżka do zasobu w paczce.
     * @param replace czy nadpisać istniejący plik.
     */
    fun saveResource(resourcePath: String, replace: Boolean)

    /**
     * Zwraca strumień do zasobu osadzonego w JAR, np. domyślnego pliku językowego.
     */
    fun getResourceStream(resourcePath: String): InputStream?
}
