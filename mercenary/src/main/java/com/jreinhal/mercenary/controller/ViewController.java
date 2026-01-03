package com.jreinhal.mercenary.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewController {

    @GetMapping("/")
    public String showDashboard() {
        return "dashboard"; // This tells Spring to load templates/dashboard.html
    }
}