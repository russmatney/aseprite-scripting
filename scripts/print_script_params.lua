app.transaction(
  "print_script_params", function()
    for k, t in pairs(app.params) do
      print("argument key:", k, "value:", t)
    end
end)
