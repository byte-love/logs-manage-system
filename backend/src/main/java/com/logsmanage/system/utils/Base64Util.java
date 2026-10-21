package com.logsmanage.system.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class Base64Util {

    final static Base64.Encoder encoder = Base64.getEncoder();
    final static Base64.Decoder decoder = Base64.getDecoder();

    //加密
    public static String encode(String text){
        return encoder.encodeToString(text.getBytes(StandardCharsets.UTF_8)).replace("/","_").replace("+","-");
    }

    //解密
    public static String decode(String encodedText){
        try{
            encodedText = doubleURLDecode(encodedText);

            return new String(decoder.decode(encodedText.replace("-","+").replace("_","/")), StandardCharsets.UTF_8);
        }catch (Exception ex){
            return null;
        }
    }


    public static void main(String[] args) throws UnsupportedEncodingException {


//        String input = "Hello,World!This,is,a,test 阿松大；1 asd1111";
//        String[] parts = input.split("[ ;,；]");
//
//        for (String part : parts) {
//            System.out.println(part);
//        }


        System.out.println(encode("07040001_2008_calculationofpromptdiphotonproductioncrosssectionsattevatronandlhcenergies"));

        System.out.println(decode("XMmmt_ij6VE3Vuaqbx6-kryVXnPlbLE1fFAmYGbY6Xj6mYyJruX__H1dxrbMpI8C9-Wx_MhYyC4qw9DhHotWNSXw3GgGsXkDYy_ANuumqxSguB2exNEfP1WAY1CSdR1AcEkKMb2ldQXdddzxIcnI47UqlxFS93bul3H-3S69-52hE07YegfatQ=="));


        String decodedString = doubleURLDecode("5YiY56WW5Z-6XzE2ODgwOTUwNDc5OTY=");

        System.out.println(decodedString);

    }
    public static String doubleURLDecode(String s) {
        try{
            String firstDecode = URLDecoder.decode(s, "UTF-8");
            return URLDecoder.decode(firstDecode, "UTF-8");
        }catch (Exception ex){
        }
        return s;
    }

}
