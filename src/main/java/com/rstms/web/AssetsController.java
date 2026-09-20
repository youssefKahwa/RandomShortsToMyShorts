package com.rstms.web;

import com.rstms.service.AssetStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/** Upload-once library for sound effects and music beds, reused by filename across many jobs. */
@Controller
@RequestMapping("/assets")
public class AssetsController {

    private final AssetStore assets;

    public AssetsController(AssetStore assets) {
        this.assets = assets;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("sfx", assets.list(AssetStore.Kind.SFX));
        model.addAttribute("music", assets.list(AssetStore.Kind.MUSIC));
        return "assets";
    }

    @PostMapping("/sfx")
    public String uploadSfx(@RequestParam("file") MultipartFile file) {
        assets.save(AssetStore.Kind.SFX, file);
        return "redirect:/assets";
    }

    @PostMapping("/music")
    public String uploadMusic(@RequestParam("file") MultipartFile file) {
        assets.save(AssetStore.Kind.MUSIC, file);
        return "redirect:/assets";
    }
}
