图生图-单张图生成单张图
curl -X POST https://ark.cn-beijing.volces.com/api/v3/images/generations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ARK_API_KEY" \
  -d '{
    "model": "doubao-seedream-4-5-251128",
    "prompt": "生成狗狗趴在草地上的近景画面",
    "image": "https://ark-project.tos-cn-beijing.volces.com/doc_image/seedream4_imageToimage.png",
    "sequential_image_generation": "disabled",
    "response_format": "url",
    "size": "2K",
    "stream": false,
    "watermark": true
}'

---
图生图-单张图生成组图
curl -X POST https://ark.cn-beijing.volces.com/api/v3/images/generations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ARK_API_KEY" \
  -d '{
    "model": "doubao-seedream-4-5-251128",
    "prompt": "参考这个LOGO，做一套户外运动品牌视觉设计，品牌名称为GREEN，包括包装袋、帽子、纸盒、手环、挂绳等。绿色视觉主色调，趣味、简约现代风格",
    "image": "https://ark-project.tos-cn-beijing.volces.com/doc_image/seedream4_imageToimages.png",
    "sequential_image_generation": "auto",
    "sequential_image_generation_options": {
        "max_images": 5
    },
    "response_format": "url",
    "size": "2K",
    "stream": true,
    "watermark": true
}'

---

图生图-多张参考图生成单张图
curl -X POST https://ark.cn-beijing.volces.com/api/v3/images/generations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ARK_API_KEY" \
  -d '{
    "model": "doubao-seedream-4-5-251128",
    "prompt": "将图1的服装换为图2的服装",
    "image": ["https://ark-project.tos-cn-beijing.volces.com/doc_image/seedream4_imagesToimage_1.png", "https://ark-project.tos-cn-beijing.volces.com/doc_image/seedream4_imagesToimage_2.png"],
    "sequential_image_generation": "disabled",
    "response_format": "url",
    "size": "2K",
    "stream": false,
    "watermark": true
}'

---

图生图-多张参考图生成组图
curl -X POST https://ark.cn-beijing.volces.com/api/v3/images/generations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ARK_API_KEY" \
  -d '{
    "model": "doubao-seedream-4-5-251128",
    "prompt": "生成3张女孩和奶牛玩偶在游乐园开心地坐过山车的图片，涵盖早晨、中午、晚上",
    "image": ["https://ark-project.tos-cn-beijing.volces.com/doc_image/seedream4_imagesToimages_1.png", "https://ark-project.tos-cn-beijing.volces.com/doc_image/seedream4_imagesToimages_2.png"],
    "sequential_image_generation": "auto",
    "sequential_image_generation_options": {
        "max_images": 3
    },
    "response_format": "url",
    "size": "2K",
    "stream": true,
    "watermark": true
}'