use super::{Region, city, region};

// doona src/dae/regions.ts, in alias priority order. ISO codes are implicit. Within a row,
// `~` marks a template-v2 substring, `#` a v2 code, and `!` a v2-only alias. Unmarked aliases
// affect display only. Region centers are approximate map anchors, in degrees (lat, lon).
#[rustfmt::skip]
pub(super) static REGIONS: &[Region] = &[
    region("HK", 22.40, 114.11, "港", &[
        city("hong-kong", 22.32, 114.17, "#hkg|~hongkong|~hong kong|~香港")]),
    region("TW", 23.70, 120.96, "~taiwan|~台灣|~台湾|~臺灣|!#twn", &[
        city("taipei", 25.03, 121.57, "tpe|~taipei|~台北")]),
    region("JP", 36.20, 138.25, "#jpn|~japan|~日本", &[
        city("tokyo", 35.68, 139.69, "~tokyo|nrt|hnd|~東京|~东京"),
        city("osaka", 34.69, 135.50, "~osaka|kix|~大阪")]),
    region("SG", 1.35, 103.82, "#sgp", &[
        city("singapore", 1.29, 103.85, "~singapore|sin|~新加坡|~獅城|~狮城")]),
    region("US", 37.09, -95.71, "#usa|~united states|america|~美國|~美国|!~silicon valley|!~硅谷", &[
        city("los-angeles", 34.05, -118.24, "lax|~los angeles|~洛杉磯|~洛杉矶"),
        city("san-jose", 37.34, -121.89, "sjc|san jose|聖何塞|圣何塞"),
        city("seattle", 47.61, -122.33, "sea|~seattle|西雅圖|~西雅图"),
        city("new-york", 40.71, -74.01, "nyc|new york|jfk|紐約|纽约"),
        city("chicago", 41.88, -87.63, "chicago|ord|芝加哥"),
        city("dallas", 32.78, -96.80, "dallas|dfw|達拉斯|达拉斯"),
        city("miami", 25.76, -80.19, "miami|mia|邁阿密|迈阿密")]),
    region("KR", 35.91, 127.77, "#kor|~korea|south korea|南韩|南韓|~韓國|~韩国", &[
        city("seoul", 37.57, 126.98, "~seoul|icn|~首爾|~首尔")]),
    region("CA", 56.13, -106.35, "can|canada|加拿大", &[
        city("toronto", 43.65, -79.38, "toronto|yyz|多倫多|多伦多"),
        city("vancouver", 49.28, -123.12, "vancouver|yvr|溫哥華|温哥华")]),
    region("GB", 55.38, -3.44, "uk|united kingdom|britain|英國|英国", &[
        city("london", 51.51, -0.13, "london|lhr|倫敦|伦敦")]),
    region("DE", 51.17, 10.45, "deu|germany|德國|德国", &[
        city("frankfurt", 50.11, 8.68, "frankfurt|fra|法蘭克福|法兰克福")]),
    region("FR", 46.23, 2.21, "france|法國|法国", &[city("paris", 48.86, 2.35, "paris|cdg|巴黎")]),
    region("NL", 52.13, 5.29, "nld|netherlands|荷蘭|荷兰", &[city("amsterdam", 52.37, 4.90, "amsterdam|ams|阿姆斯特丹")]),
    region("RU", 61.52, 105.32, "rus|russia|俄羅斯|俄罗斯", &[city("moscow", 55.76, 37.62, "moscow|莫斯科")]),
    region("AU", -25.27, 133.78, "aus|australia|澳洲|澳大利亞|澳大利亚", &[
        city("sydney", -33.87, 151.21, "sydney|syd|悉尼|雪梨"),
        city("melbourne", -37.81, 144.96, "melbourne|mel|墨爾本|墨尔本")]),
    region("IN", 20.59, 78.96, "ind|india|印度", &[city("mumbai", 19.08, 72.88, "mumbai|bom|孟買|孟买")]),
    region("TR", 38.96, 35.24, "tur|turkey|türkiye|土耳其", &[city("istanbul", 41.01, 28.98, "istanbul")]),
    region("BR", -14.24, -51.93, "bra|brazil|巴西", &[city("sao-paulo", -23.55, -46.63, "sao paulo|são paulo|gru|聖保羅|圣保罗")]),
    region("AR", -38.42, -63.62, "arg|argentina|阿根廷", &[]),
    region("MY", 4.21, 101.98, "mys|malaysia|馬來西亞|马来西亚", &[city("kuala-lumpur", 3.139, 101.69, "kul|kuala lumpur|吉隆坡")]),
    region("TH", 15.87, 100.99, "tha|thailand|泰國|泰国", &[city("bangkok", 13.76, 100.50, "bangkok|bkk")]),
    region("VN", 14.06, 108.28, "vnm|vietnam|越南", &[]),
    region("PH", 12.88, 121.77, "phl|philippines|菲律賓|菲律宾", &[city("manila", 14.60, 120.98, "manila")]),
    region("ID", -0.79, 113.92, "idn|indonesia|印度尼西亚|印度尼西亞|印尼", &[city("jakarta", -6.21, 106.85, "jakarta")]),
    region("AE", 23.42, 53.85, "are|uae|united arab emirates|阿联酋|阿聯酋|阿聯|阿联", &[city("dubai", 25.20, 55.27, "dubai|dxb|杜拜|迪拜")]),
    region("CH", 46.82, 8.23, "che|switzerland|瑞士", &[city("zurich", 47.38, 8.54, "zurich")]),
    region("SE", 60.13, 18.64, "swe|sweden|瑞典", &[city("stockholm", 59.33, 18.07, "stockholm")]),
    region("FI", 61.92, 25.75, "fin|finland|芬蘭|芬兰", &[city("helsinki", 60.17, 24.94, "helsinki")]),
    region("IT", 41.87, 12.57, "ita|italy|義大利|意大利", &[city("milan", 45.46, 9.19, "milan")]),
    region("ES", 40.46, -3.75, "esp|spain|西班牙", &[city("madrid", 40.42, -3.70, "madrid")]),
    region("PL", 51.92, 19.15, "pol|poland|波蘭|波兰", &[city("warsaw", 52.23, 21.01, "warsaw")]),
    region("MO", 22.20, 113.54, "mac", &[city("macao", 22.20, 113.54, "macau|macao|澳門|澳门")]),
    region("NZ", -40.90, 174.89, "new zealand|新西兰|紐西蘭", &[]),
    region("MX", 23.63, -102.55, "mexico|墨西哥", &[]),
    region("NO", 60.47, 8.47, "norway|挪威", &[]),
    region("DK", 56.26, 9.50, "denmark|丹麦|丹麥", &[]),
    region("IE", 53.41, -8.24, "ireland|爱尔兰|愛爾蘭", &[]),
    region("ZA", -30.56, 22.94, "south africa|南非", &[]),
    region("IL", 31.05, 34.85, "israel|以色列", &[]),
    region("UA", 48.38, 31.17, "ukraine|乌克兰|烏克蘭", &[]),
    region("PT", 39.40, -8.22, "portugal|葡萄牙", &[]),
    region("CN", 35.86, 104.20, "chn|china|中國|中国", &[
        city("shanghai", 31.23, 121.47, "shanghai|上海"), city("beijing", 39.90, 116.41, "beijing|北京"),
        city("shenzhen", 22.54, 114.06, "shenzhen|深圳")]),
    region("KZ", 48.02, 66.92, "kazakhstan|哈萨克斯坦|哈薩克斯坦|哈萨克|哈薩克", &[city("almaty", 43.24, 76.89, "almaty")]),
    region("AT", 47.52, 14.55, "austria|奥地利|奧地利", &[city("vienna", 48.21, 16.37, "vienna")]),
    region("BE", 50.50, 4.47, "belgium|比利时|比利時", &[city("brussels", 50.85, 4.35, "brussels")]),
    region("LU", 49.82, 6.13, "luxembourg|卢森堡|盧森堡", &[city("luxembourg-city", 49.61, 6.13, "luxembourg city")]),
    region("CZ", 49.82, 15.47, "czech|czechia|捷克", &[city("prague", 50.08, 14.44, "prague")]),
    region("RO", 45.94, 24.97, "romania|罗马尼亚|羅馬尼亞", &[city("bucharest", 44.43, 26.10, "bucharest")]),
    region("IS", 64.96, -19.02, "iceland|冰岛|冰島", &[city("reykjavik", 64.15, -21.94, "reykjavik")]),
    region("CL", -35.68, -71.54, "chile|智利", &[city("santiago", -33.45, -70.67, "santiago")]),
    region("CO", 4.57, -74.30, "colombia|哥伦比亚|哥倫比亞", &[city("bogota", 4.71, -74.07, "bogota")]),
    region("SA", 23.89, 45.08, "saudi|沙特|沙烏地", &[city("riyadh", 24.71, 46.68, "riyadh")]),
    region("PK", 30.38, 69.35, "pakistan|巴基斯坦", &[city("karachi", 24.86, 67.01, "karachi")]),
    region("NG", 9.08, 8.68, "nigeria|尼日利亚|尼日利亞|奈及利亞", &[city("lagos", 6.52, 3.38, "lagos")]),
    region("EG", 26.82, 30.80, "egypt|埃及", &[city("cairo", 30.04, 31.24, "cairo")]),
    region("KH", 12.57, 104.99, "cambodia|柬埔寨", &[]),
];
