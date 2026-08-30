[CmdletBinding()]
param(
    [string]$OutputPath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'windows\PhoneSyncDataReader\Assets\PhoneSyncDataReader.ico')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function New-PhoneGlassesPng {
    param([int]$Size)

    $bitmap = [System.Drawing.Bitmap]::new($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $scale = $Size / 48.0
        $blue = [System.Drawing.ColorTranslator]::FromHtml('#1976D2')
        $ink = [System.Drawing.ColorTranslator]::FromHtml('#202523')
        $white = [System.Drawing.Color]::White

        function ConvertTo-IconUnit([single]$value) { return [single]($value * $scale) }
        function RoundedRectangle([single]$x, [single]$y, [single]$width, [single]$height, [single]$radius) {
            $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
            $diameter = 2 * $radius
            $path.AddArc($x, $y, $diameter, $diameter, 180, 90)
            $path.AddArc($x + $width - $diameter, $y, $diameter, $diameter, 270, 90)
            $path.AddArc($x + $width - $diameter, $y + $height - $diameter, $diameter, $diameter, 0, 90)
            $path.AddArc($x, $y + $height - $diameter, $diameter, $diameter, 90, 90)
            $path.CloseFigure()
            return $path
        }

        $blueBrush = [System.Drawing.SolidBrush]::new($blue)
        $whiteBrush = [System.Drawing.SolidBrush]::new($white)
        $inkBrush = [System.Drawing.SolidBrush]::new($ink)
        $strokeWidth = [single](2.5 * $scale)
        $inkPen = [System.Drawing.Pen]::new($ink, $strokeWidth)
        $inkPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $inkPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        $inkPen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
        try {
            $phone = RoundedRectangle (ConvertTo-IconUnit 8) (ConvertTo-IconUnit 3) (ConvertTo-IconUnit 32) (ConvertTo-IconUnit 42) (ConvertTo-IconUnit 7)
            $screen = RoundedRectangle (ConvertTo-IconUnit 13) (ConvertTo-IconUnit 8) (ConvertTo-IconUnit 22) (ConvertTo-IconUnit 31) (ConvertTo-IconUnit 2)
            try {
                $graphics.FillPath($blueBrush, $phone)
                $graphics.FillPath($whiteBrush, $screen)
            } finally {
                $phone.Dispose()
                $screen.Dispose()
            }
            $graphics.FillEllipse($blueBrush, (ConvertTo-IconUnit 20), (ConvertTo-IconUnit 40), (ConvertTo-IconUnit 4), (ConvertTo-IconUnit 4))

            $leftLens = RoundedRectangle (ConvertTo-IconUnit 14) (ConvertTo-IconUnit 18) (ConvertTo-IconUnit 10) (ConvertTo-IconUnit 11) (ConvertTo-IconUnit 2)
            $rightLens = RoundedRectangle (ConvertTo-IconUnit 24) (ConvertTo-IconUnit 18) (ConvertTo-IconUnit 10) (ConvertTo-IconUnit 11) (ConvertTo-IconUnit 2)
            try {
                $graphics.DrawPath($inkPen, $leftLens)
                $graphics.DrawPath($inkPen, $rightLens)
            } finally {
                $leftLens.Dispose()
                $rightLens.Dispose()
            }
            $graphics.DrawLine($inkPen, (ConvertTo-IconUnit 13), (ConvertTo-IconUnit 23), (ConvertTo-IconUnit 14), (ConvertTo-IconUnit 23))
            $graphics.DrawLine($inkPen, (ConvertTo-IconUnit 24), (ConvertTo-IconUnit 23), (ConvertTo-IconUnit 25), (ConvertTo-IconUnit 23))
            $graphics.DrawLine($inkPen, (ConvertTo-IconUnit 34), (ConvertTo-IconUnit 23), (ConvertTo-IconUnit 35), (ConvertTo-IconUnit 23))

            $smile = [System.Drawing.Drawing2D.GraphicsPath]::new()
            try {
                $smile.AddBezier((ConvertTo-IconUnit 18), (ConvertTo-IconUnit 32), (ConvertTo-IconUnit 20), (ConvertTo-IconUnit 36), (ConvertTo-IconUnit 28), (ConvertTo-IconUnit 36), (ConvertTo-IconUnit 30), (ConvertTo-IconUnit 32))
                $smile.AddBezier((ConvertTo-IconUnit 30), (ConvertTo-IconUnit 32), (ConvertTo-IconUnit 29), (ConvertTo-IconUnit 36), (ConvertTo-IconUnit 27), (ConvertTo-IconUnit 37), (ConvertTo-IconUnit 24), (ConvertTo-IconUnit 37))
                $smile.AddBezier((ConvertTo-IconUnit 24), (ConvertTo-IconUnit 37), (ConvertTo-IconUnit 21), (ConvertTo-IconUnit 37), (ConvertTo-IconUnit 19), (ConvertTo-IconUnit 36), (ConvertTo-IconUnit 18), (ConvertTo-IconUnit 32))
                $smile.CloseFigure()
                $graphics.FillPath($inkBrush, $smile)
            } finally {
                $smile.Dispose()
            }
        } finally {
            $blueBrush.Dispose()
            $whiteBrush.Dispose()
            $inkBrush.Dispose()
            $inkPen.Dispose()
        }

        $stream = [System.IO.MemoryStream]::new()
        $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        return ,$stream.ToArray()
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

$sizes = @(16, 24, 32, 48, 64, 128, 256)
$images = [System.Collections.Generic.List[byte[]]]::new()
foreach ($size in $sizes) {
    $images.Add([byte[]](New-PhoneGlassesPng -Size $size))
}
$directory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$stream = [System.IO.File]::Create($OutputPath)
$writer = [System.IO.BinaryWriter]::new($stream)
try {
    $writer.Write([uint16]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]$sizes.Count)
    $offset = 6 + (16 * $sizes.Count)
    for ($index = 0; $index -lt $sizes.Count; $index++) {
        $size = $sizes[$index]
        $dimension = [byte]$(if ($size -eq 256) { 0 } else { $size })
        $writer.Write($dimension)
        $writer.Write($dimension)
        $writer.Write([byte]0)
        $writer.Write([byte]0)
        $writer.Write([uint16]1)
        $writer.Write([uint16]32)
        $writer.Write([uint32]$images[$index].Length)
        $writer.Write([uint32]$offset)
        $offset += $images[$index].Length
    }
    foreach ($image in $images) { $writer.Write($image) }
} finally {
    $writer.Dispose()
    $stream.Dispose()
}

Write-Host "Generated Windows icon: $OutputPath"