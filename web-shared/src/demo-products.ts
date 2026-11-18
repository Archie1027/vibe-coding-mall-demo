// Explicit development fixtures, not authoritative catalog or inventory.
export const demoProducts = [
  {
    "id": "2001",
    "name": "GeForce RTX 4080 SUPER 16GB 显卡",
    "category": "显卡",
    "brand": "NVIDIA",
    "priceMinor": "899900",
    "rating": "4.9",
    "brandLine": "NVIDIA · GEFORCE",
    "tag": "旗舰精选",
    "image": "/assets/products/rtx-4080-super.jpg",
    "imageAlt": "NVIDIA GeForce RTX 4080 SUPER 显卡产品图",
    "art": "device"
  },
  {
    "id": "2002",
    "name": "Core i7-14700K 高性能处理器",
    "category": "处理器",
    "brand": "Intel",
    "priceMinor": "289900",
    "rating": "4.8",
    "brandLine": "INTEL · CORE",
    "tag": "",
    "image": "/assets/products/intel-i7-14700k.jpg",
    "imageAlt": "Intel Core i7-14700K 处理器包装盒",
    "art": "device"
  },
  {
    "id": "2003",
    "name": "990 PRO 2TB PCIe 4.0 固态硬盘",
    "category": "内存",
    "brand": "Samsung",
    "priceMinor": "119900",
    "rating": "4.9",
    "brandLine": "SAMSUNG · SSD",
    "tag": "",
    "image": "/assets/products/samsung-990-pro-2tb.jpg",
    "imageAlt": "Samsung 990 PRO 2TB 固态硬盘产品图",
    "art": "device"
  },
  {
    "id": "2004",
    "name": "ROG 电竞机械键盘 RGB 版",
    "category": "外设",
    "brand": "ROG",
    "priceMinor": "89900",
    "rating": "4.7",
    "brandLine": "ROG · GAMING",
    "tag": "",
    "image": "/assets/products/rog-strix-scope.webp",
    "imageAlt": "ROG 电竞机械键盘产品图",
    "art": "keyboard"
  },
  {
    "id": "2005",
    "name": "GeForce RTX 4070 SUPER",
    "category": "显卡",
    "brand": "NVIDIA",
    "priceMinor": "529900",
    "rating": "4.8",
    "brandLine": "NVIDIA · GEFORCE",
    "tag": "",
    "image": "/assets/products/rtx-4070-super.jpg",
    "imageAlt": "NVIDIA GeForce RTX 4070 SUPER 显卡产品图",
    "art": "device"
  },
  {
    "id": "2006",
    "name": "980 1TB NVMe 固态硬盘",
    "category": "内存",
    "brand": "Samsung",
    "priceMinor": "69900",
    "rating": "4.6",
    "brandLine": "SAMSUNG · SSD",
    "tag": "",
    "image": "/assets/products/samsung-980-1tb.png",
    "imageAlt": "Samsung 980 1TB 固态硬盘产品图",
    "art": "device"
  }
] as const
export const demoOrders = [
  {
    "status": "待发货",
    "text": "ZC2026102000312026-10-20 14:32林先生¥ 12,499.00华东一号仓待发货已支付查看详情",
    "date": "2026-10-20",
    "warehouse": "华东一号仓",
    "source": "商城前台"
  },
  {
    "status": "待付款",
    "text": "ZC2026102000302026-10-20 14:18陈女士¥ 8,999.00华南二号仓待付款未支付查看详情",
    "date": "2026-10-20",
    "warehouse": "华南二号仓",
    "source": "商城前台"
  },
  {
    "status": "已发货",
    "text": "ZC2026102000292026-10-20 13:56星河科技¥ 26,800.00华东一号仓已发货已支付查看详情",
    "date": "2026-10-20",
    "warehouse": "华东一号仓",
    "source": "商城前台"
  },
  {
    "status": "已完成",
    "text": "ZC2026102000282026-10-19 18:21赵先生¥ 2,899.00华北三号仓已完成已支付查看详情",
    "date": "2026-10-19",
    "warehouse": "华北三号仓",
    "source": "商城前台"
  },
  {
    "status": "售后中",
    "text": "ZC2026102000272026-10-19 16:03陈女士¥ 1,199.00华南二号仓售后中已支付查看详情",
    "date": "2026-10-19",
    "warehouse": "华南二号仓",
    "source": "商城前台"
  }
] as const
