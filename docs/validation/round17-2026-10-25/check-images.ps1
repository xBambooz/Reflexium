param([string]$Directory = $PSScriptRoot)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing @'
using System;
using System.IO;
using System.Drawing;
using System.Collections.Generic;
public static class Round17Images {
 static double[] Read(Bitmap image,int half) {
  var values=new double[320*180];
  for(int y=0;y<180;y++)for(int x=0;x<320;x++){var p=image.GetPixel(x+half*320,y);values[y*320+x]=.2126*p.R+.7152*p.G+.0722*p.B;}
  return values;
 }
 static Dictionary<string,object> Stats(double[] a) {
  double sum=0,square=0;int edges=0;
  for(int i=0;i<a.Length;i++){sum+=a[i];square+=a[i]*a[i];if(i%320>0&&Math.Abs(a[i]-a[i-1])>12)edges++;if(i>=320&&Math.Abs(a[i]-a[i-320])>12)edges++;}
  double mean=sum/a.Length,variance=square/a.Length-mean*mean;
  return new Dictionary<string,object>{{"mean_luma",mean},{"variance",variance},{"edges",edges},{"content_valid",mean>8&&variance>80&&edges>150}};
 }
 static Dictionary<string,object> Correlation(double[] a,double[] b) {
  double best=-1,direct=-1;int bx=0,by=0;
  for(int sy=-12;sy<=12;sy+=2)for(int sx=-12;sx<=12;sx+=2) {
   double sa=0,sb=0,saa=0,sbb=0,sab=0;int n=0;
   for(int y=12;y<168;y+=2)for(int x=12;x<308;x+=2) {double va=a[y*320+x],vb=b[(y+sy)*320+x+sx];sa+=va;sb+=vb;saa+=va*va;sbb+=vb*vb;sab+=va*vb;n++;}
   double divisor=Math.Sqrt((saa-sa*sa/n)*(sbb-sb*sb/n));double value=divisor>0?(sab-sa*sb/n)/divisor:0;
   if(sx==0&&sy==0)direct=value;if(value>best){best=value;bx=sx;by=sy;}
  }
  return new Dictionary<string,object>{{"direct",direct},{"aligned",best},{"shift_x",bx},{"shift_y",by},{"valid",direct>.65&&best>.75}};
 }
 public static object Inspect(string directory) {
  var output=new List<object>();
  foreach(string file in System.IO.Directory.GetFiles(directory,"full-*.png")) {
   using(var image=new Bitmap(file)) {
    if(image.Width!=640||image.Height!=180)throw new Exception("Unexpected crop dimensions: "+file);
    string stem=Path.GetFileNameWithoutExtension(file);int split=stem.LastIndexOf('-');string reference=Path.Combine(directory,stem.Substring(0,split)+"-native.png");
    if(!File.Exists(reference))throw new Exception("Missing native reference: "+reference);
    using(var native=new Bitmap(reference)) {
     var left=Read(image,0);var right=Read(image,1);
     output.Add(new Dictionary<string,object>{{"key",stem},{"width",image.Width},{"height",image.Height},{"left",Stats(left)},{"right",Stats(right)},{"left_native",Correlation(left,Read(native,0))},{"right_native",Correlation(right,Read(native,1))},{"repeat",Correlation(left,right)}});
    }
   }
  }
  return output;
 }
}
'@
$result = [Round17Images]::Inspect($Directory) | ConvertTo-Json -Depth 6
$result | Set-Content -LiteralPath (Join-Path $Directory 'image-check.json') -Encoding UTF8
$result
