<?php
declare(strict_types=1);

/*
 * Router for `php -S`: streams one registered file to the one device it was registered for.
 * Anything else - an unknown token, another address, another method - gets a bare 404, so the
 * port reveals nothing to the rest of the network. Byte ranges are honoured so players can seek.
 */

$state = getenv('JPLAY_REMOTE_STATE') ?: '';
$path = (string)parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
$token = explode('/', ltrim($path, '/'))[0] ?? '';
$files = json_decode((string)@file_get_contents($state . '/files.json'), true) ?: [];
$entry = $files[$token] ?? null;
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($state === '' || !is_array($entry) || !in_array($_SERVER['REMOTE_ADDR'] ?? '', $entry['allow'] ?? [], true)
    || !in_array($method, ['GET', 'HEAD'], true) || !is_file($entry['path']) || !is_readable($entry['path'])) {
    http_response_code(404);
    return true;
}

$file = $entry['path'];
$size = filesize($file);
$types = [
    'mkv' => 'video/x-matroska', 'mp4' => 'video/mp4', 'm4v' => 'video/mp4', 'webm' => 'video/webm',
    'avi' => 'video/x-msvideo', 'mov' => 'video/quicktime', 'ts' => 'video/mp2t', 'mp3' => 'audio/mpeg',
    'flac' => 'audio/flac', 'm4a' => 'audio/mp4', 'ogg' => 'audio/ogg', 'opus' => 'audio/ogg', 'wav' => 'audio/wav',
];
$first = 0;
$last = $size - 1;

if (preg_match('/^bytes=(\d*)-(\d*)$/', $_SERVER['HTTP_RANGE'] ?? '', $range) && ($range[1] !== '' || $range[2] !== '')) {
    if ($range[1] === '') {
        $first = max(0, $size - (int)$range[2]);
    } else {
        $first = (int)$range[1];
        $last = $range[2] === '' ? $size - 1 : min((int)$range[2], $size - 1);
    }
    if ($first > $last || $first >= $size) {
        http_response_code(416);
        header('Content-Range: bytes */' . $size);
        return true;
    }
    http_response_code(206);
    header('Content-Range: bytes ' . $first . '-' . $last . '/' . $size);
}

header('Content-Type: ' . ($types[strtolower(pathinfo($file, PATHINFO_EXTENSION))] ?? 'application/octet-stream'));
header('Accept-Ranges: bytes');
header('Content-Length: ' . ($last - $first + 1));
header('Cache-Control: no-store');
if ($method === 'HEAD') {
    return true;
}

set_time_limit(0);
while (ob_get_level() > 0) {
    ob_end_flush();
}
$handle = fopen($file, 'rb');
fseek($handle, $first);
$remaining = $last - $first + 1;
while ($remaining > 0 && !feof($handle) && !connection_aborted()) {
    $chunk = fread($handle, (int)min(1048576, $remaining));
    if ($chunk === false || $chunk === '') {
        break;
    }
    echo $chunk;
    flush();
    $remaining -= strlen($chunk);
}
fclose($handle);
return true;
